package com.libseat.scheduler;

import com.libseat.common.BizConstants;
import com.libseat.entity.Group;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.service.GroupService;
import com.libseat.service.LeaveStateService;
import com.libseat.service.ReservationStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务（单机，无 MQ）。
 *
 * <ol>
 *   <li>每分钟：CONFIRMED 且「开始 + 15 分钟 &lt; 现在」→ NO_SHOW（删 slot、违约次数 +1）；</li>
 *   <li>每分钟：OPEN 暂离且「应归时刻 &lt; 现在」→ VIOLATED + 暂离记 TIMEOUT（V2 §5.3）；</li>
 *   <li>每 5 分钟：CHECKED_IN 且「结束 &lt; 现在」→ COMPLETED（删 slot）。</li>
 * </ol>
 *
 * <p>本类只负责「扫描 + 判定」，落库写操作交给 {@link ReservationStateService} 与
 * {@link com.libseat.service.LeaveStateService}，以保证单条记录的「状态归档」与「slot 释放」
 * 在同一事务内完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private final ReservationRepository reservationRepository;

    private final ReservationStateService reservationStateService;

    private final LeaveRecordRepository leaveRecordRepository;

    private final LeaveStateService leaveStateService;

    private final com.libseat.service.RuleConfigService ruleConfigService;

    private final GroupRepository groupRepository;

    private final GroupService groupService;

    /**
     * 扫描超时未签到。
     *
     * <p>逐条独立处理：单条失败只记录日志并继续，不影响本轮其余记录
     * （否则一条脏数据会中断整轮扫描，导致后续座位长期无法释放）。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 10_000L)
    public void scanNoShow() {
        LocalDate today = LocalDate.now();
        List<Reservation> candidates = reservationRepository.findByStatusAndDateRange(
                ReservationStatus.CONFIRMED, today.minusDays(1), today);
        LocalDateTime now = LocalDateTime.now();
        int checkInAfterMin = ruleConfigService.checkInAfterMin();
        int count = 0;
        for (Reservation reservation : candidates) {
            LocalDateTime start = BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot());
            if (!start.plusMinutes(checkInAfterMin).isBefore(now)) {
                continue;
            }
            try {
                count += reservationStateService.markNoShow(reservation, now);
            } catch (Exception e) {
                log.error("置 NO_SHOW 失败：reservationId={}", reservation.getId(), e);
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 条预约因超时未签到置为 NO_SHOW", count);
        }
    }

    /**
     * 归档已结束的预约。
     *
     * <p>同样逐条独立处理，单条失败不影响整轮。
     */
    @Scheduled(fixedRate = 300_000L, initialDelay = 20_000L)
    public void completeExpired() {
        LocalDate today = LocalDate.now();
        List<Reservation> candidates = reservationRepository.findByStatusAndDateRange(
                ReservationStatus.CHECKED_IN, today.minusDays(1), today);
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (Reservation reservation : candidates) {
            LocalDateTime end = BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot());
            if (!end.isBefore(now)) {
                continue;
            }
            try {
                count += reservationStateService.markCompleted(reservation, now);
            } catch (Exception e) {
                log.error("置 COMPLETED 失败：reservationId={}", reservation.getId(), e);
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 条已签到预约归档为 COMPLETED", count);
        }
    }

    /**
     * 扫描暂离超时（DESIGN §5.3）：OPEN 暂离且已过应归时刻 → 预约 VIOLATED + 座位释放。
     *
     * <p>逐条 try-catch：关联预约缺失这类脏数据只记 error，必须继续处理其余记录，
     * 否则一条脏数据会让后面所有超时座位长期放不出来。
     * 重复扫描天然幂等：处理完 {@code open_flag} 归 NULL，本查询再也选不到它。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 30_000L)
    public void scanLeaveTimeout() {
        LocalDateTime now = LocalDateTime.now();
        List<LeaveRecord> overdue = leaveRecordRepository.findByStatusAndOpenFlagAndPlannedEndTimeBefore(
                LeaveStatus.OPEN, LeaveStatus.OPEN_FLAG, now);
        int count = 0;
        for (LeaveRecord record : overdue) {
            try {
                Reservation reservation = reservationRepository.findById(record.getReservationId()).orElse(null);
                if (reservation == null) {
                    // 预约不在了（历史上被直接删行）：只结掉暂离本身，别让脏数据卡住整轮
                    log.error("暂离超时的关联预约缺失，仅结束暂离记录：leaveId={}, reservationId={}",
                            record.getId(), record.getReservationId());
                    record.setStatus(LeaveStatus.TIMEOUT);
                    record.setOpenFlag(null);
                    leaveRecordRepository.save(record);
                    continue;
                }
                if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
                    log.error("暂离记录仍 OPEN 但预约已是 {}，只结束暂离：leaveId={}, reservationId={}",
                            reservation.getStatus(), record.getId(), reservation.getId());
                    record.setStatus(LeaveStatus.TIMEOUT);
                    record.setOpenFlag(null);
                    leaveRecordRepository.save(record);
                    continue;
                }
                count += leaveStateService.markTimeout(record, reservation, now);
            } catch (Exception e) {
                log.error("暂离超时处理失败：leaveId={}", record.getId(), e);
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 条暂离超时，预约归档为 VIOLATED 并释放座位", count);
        }
    }

    /**
     * 扫描到点未锁定的队伍（DESIGN §7.2）：到 {@code lock_time} 即冻结成员并按桌下单。
     *
     * <p>★ 逐队 try-catch 且判定交给 {@link GroupService#lockAndOrder}（独立 Bean 的事务代理）：
     * 一支队伍因座位被抢而 4091 回滚，不能连带打断同轮其他队伍的下单。
     * 幂等靠状态判断 —— 成功变 RESERVED、不足变 FAILED，下一轮都选不到 {@code FORMING} 之外的队。
     * 失败的队伍留在 {@code FORMING} 每分钟重试，直到开会时刻由缺席链路收尾；
     * 「组长为什么没成」的告知属于 T-V2-20 通知中心，这里先只保证不打扰别人。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 35_000L)
    public void scanGroupLock() {
        LocalDateTime now = LocalDateTime.now();
        List<Group> due = groupRepository.findDueForLock(GroupStatus.FORMING, now);
        int count = 0;
        for (Group group : due) {
            try {
                if (groupService.lockAndOrder(group.getId())) {
                    count++;
                }
            } catch (Exception e) {
                log.error("队伍锁定下单失败，下一轮重试：groupId={}", group.getId(), e);
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 支队伍已锁定并完成整桌下单", count);
        }
    }
}

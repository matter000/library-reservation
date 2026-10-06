package com.libseat.scheduler;

import com.libseat.common.BizConstants;
import com.libseat.entity.Group;
import com.libseat.entity.GroupMember;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.service.GroupService;
import com.libseat.service.GroupStateService;
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

    private final GroupStateService groupStateService;

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
            if (reservation.getGroupId() != null) {
                // ★ 队伍预约不走个人 NO_SHOW：签到窗口一过，个人扫描会给全桌每人记一次爽约，
                //   而同一轮的 scanGroupAttendance 还要按「组长缺席」再处理一次 —— 两套判定只能有一套。
                continue;
            }
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
     * 下单失败的队伍留在 {@code FORMING} 每分钟重试，但<b>过了开会时刻就由
     * {@link GroupService#abandonIfExpired} 收尾成 FAILED</b>：那时重试永远不可能成功，
     * 而缺席链路只处理 RESERVED/CHECKED_IN，救不了一支从没下成单的队。
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
                log.error("队伍锁定下单失败：groupId={}", group.getId(), e);
                // ★ 通知必须写在这里而不是 lockAndOrder 里面：那一笔事务已经随异常回滚，
                //   在里面写的通知会一起消失（B10 —— 组长看到的界面只会停在「等待按桌下单」）
                try {
                    groupService.notifyLockAttemptFailed(group.getId(), e.getMessage());
                } catch (Exception notifyEx) {
                    log.error("队伍失败告知失败：groupId={}", group.getId(), notifyEx);
                }
                try {
                    groupService.abandonIfExpired(group.getId(), now, e.getMessage());
                } catch (Exception ex) {
                    log.error("队伍收尾失败：groupId={}", group.getId(), ex);
                }
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 支队伍已锁定并完成整桌下单", count);
        }
    }

    /**
     * 扫描队伍签到与缺席（DESIGN §7.2）：两类判定同轮处理。
     *
     * <ol>
     *   <li>队伍已签到但成员过了宽限期仍未确认入座 → {@code ABSENT} + 违规 −10，
     *       <b>座位不释放</b>（整桌是一个使用单元）；</li>
     *   <li>队伍已订座但组长错过签到窗口 → 整队取消 + 释放座位 + 组长 −20，成员不记违规。</li>
     * </ol>
     *
     * <p>★ 逐队逐人 try-catch 且写操作走 {@link GroupStateService} 的事务代理：
     * 一条脏数据不能挡住同轮其他队伍，而「改预约 + 删 slot + 记违规」必须同事务，
     * 否则会出现队伍取消了但座位还占着。幂等靠状态本身：成员离开 {@code UNKNOWN}、
     * 队伍离开 {@code RESERVED}，下一轮都选不到同一条。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 40_000L)
    public void scanGroupAttendance() {
        LocalDateTime now = LocalDateTime.now();
        int absent = 0;
        for (Group group : groupRepository.findByStatus(GroupStatus.CHECKED_IN)) {
            for (GroupMember member : groupStateService.overdueAbsentMembers(group, now)) {
                try {
                    groupStateService.markMemberAbsent(member, group, now);
                    absent++;
                } catch (Exception e) {
                    log.error("成员缺席处理失败：groupId={}, userId={}",
                            group.getId(), member.getUserId(), e);
                }
            }
        }
        int cancelled = 0;
        for (Group group : groupRepository.findByStatus(GroupStatus.RESERVED)) {
            try {
                if (groupStateService.leaderMissedCheckIn(group, now)) {
                    cancelled += groupStateService.cancelForLeaderAbsence(group, now);
                }
            } catch (Exception e) {
                log.error("组长缺席整队取消失败：groupId={}", group.getId(), e);
            }
        }
        if (absent > 0 || cancelled > 0) {
            log.info("定时任务：{} 名组队成员记为未入座，{} 条预约因组长未签到被取消", absent, cancelled);
        }
    }
}

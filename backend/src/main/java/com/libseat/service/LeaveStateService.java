package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/**
 * 暂离超时的状态流转事务边界（DESIGN §5.3）。
 *
 * <p>★ 与 {@link ReservationStateService} 同一条 V1 教训：声明式事务靠 AOP 代理，
 * 扫描器里直接写这些步骤（或同类内部自调用）会让 {@code @Transactional} 静默失效，
 * 结果是「预约已归档但 slot 行还在」——座位永远放不出来且不报错。
 * 因此扫描器只做判定，写操作一律经本 Bean 的代理进入同一个事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveStateService {

    private final LeaveRecordRepository leaveRecordRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final UserRepository userRepository;

    private final WaitlistService waitlistService;

    private final CreditService creditService;

    /**
     * 单条超时处理：预约归档 + 释放 slot + 暂离记时 + 违约计数 + 候补挂钩。
     *
     * <p>五步全在一个事务里：任一步失败整轮该条回滚，不会留下半套状态。
     *
     * @param record     超时的暂离记录
     * @param reservation 对应预约
     * @param now        当前时刻
     * @return 处理条数（固定 1，便于调用方累加）
     */
    @Transactional
    public int markTimeout(LeaveRecord record, Reservation reservation, LocalDateTime now) {
        reservation.setStatus(ReservationStatus.VIOLATED);
        reservation.setCancelTime(now);
        reservationRepository.save(reservation);
        reservationSlotRepository.deleteByReservationId(reservation.getId());

        record.setStatus(LeaveStatus.TIMEOUT);
        // ★ open_flag 归 NULL：既不占用「一条预约至多一条 OPEN」的名额，也让重复扫描天然跳过
        record.setOpenFlag(null);
        leaveRecordRepository.save(record);

        userRepository.findById(record.getUserId()).ifPresent(user -> {
            int noShow = (user.getNoShowCount() == null ? 0 : user.getNoShowCount()) + 1;
            user.setNoShowCount(noShow);
            userRepository.save(user);
        });

        waitlistService.onSeatReleased(reservation);
        // ★ 证据由触发方组装：规则名 + 关键时间戳 + 涉及 ID，缺证据的违规在 CreditService 里就被拒
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "zone.leave_max_min");
        evidence.put("leaveId", record.getId());
        evidence.put("seatId", reservation.getSeatId());
        evidence.put("leaveStart", BizConstants.formatDateTime(record.getStartTime()));
        evidence.put("leaveDeadline", BizConstants.formatDateTime(record.getPlannedEndTime()));
        evidence.put("judgedAt", BizConstants.formatDateTime(now));
        creditService.applyViolation(record.getUserId(), ViolationType.LEAVE_TIMEOUT, null,
                creditService.buildEvidence(evidence), reservation.getId(), null);
        log.info("暂离超时：leaveId={}, reservationId={}, seatId={}, 应归={}",
                record.getId(), reservation.getId(), reservation.getSeatId(), record.getPlannedEndTime());
        return 1;
    }
}

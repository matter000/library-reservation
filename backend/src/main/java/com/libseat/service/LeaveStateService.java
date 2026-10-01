package com.libseat.service;

import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        // TODO(S3) T-V2-12：CreditService.applyViolation(LEAVE_TIMEOUT, -10) + 处罚评估 + 通知。
        //   本片按任务要求先写 t_user.no_show_count 这个 V1 兼容字段，避免 S2 提前依赖信用账本。
        log.info("暂离超时：leaveId={}, reservationId={}, seatId={}, 应归={}",
                record.getId(), reservation.getId(), reservation.getSeatId(), record.getPlannedEndTime());
        return 1;
    }
}

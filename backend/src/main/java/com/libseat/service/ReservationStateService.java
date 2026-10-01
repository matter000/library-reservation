package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.entity.Reservation;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
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
 * 预约状态流转的事务边界（NO_SHOW / COMPLETED）。
 *
 * <p>★ 为什么不直接写在 {@link com.libseat.scheduler.ReservationScheduler} 里：
 * Spring 的声明式事务依赖 AOP 代理，同类内部直接调用（{@code this.markNoShow(...)}）
 * 会绕过代理，{@code @Transactional} 静默失效。那样「状态归档」与「slot 释放」
 * 会退化成两个彼此独立的事务 —— 一旦后者失败，座位就残留一条永远无法释放的占用记录，
 * 且没有任何异常抛出。抽成独立 Bean 后经代理调用，两个写操作才真正在同一事务内。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationStateService {

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final UserRepository userRepository;

    private final CreditService creditService;

    private final RuleConfigService ruleConfigService;

    /**
     * 单条置 NO_SHOW：归档预约 + 释放 slot + 违约次数 +1 + 记一笔违规。
     *
     * <p>★ V1 只有计数、V2 起必须有账：{@code no_show_count} 继续维护是为了兼容
     * 已上线的展示位与用例，扣分与处罚一律由 {@link CreditService} 这个唯一入口写。
     *
     * @param reservation 预约
     * @param now         当前时间
     * @return 处理条数（固定 1，便于调用方累加计数）
     */
    @Transactional
    public int markNoShow(Reservation reservation, LocalDateTime now) {
        reservation.setStatus(ReservationStatus.NO_SHOW);
        reservation.setCancelTime(now);
        reservationRepository.save(reservation);
        reservationSlotRepository.deleteByReservationId(reservation.getId());
        userRepository.findById(reservation.getUserId()).ifPresent(user -> {
            int noShow = (user.getNoShowCount() == null ? 0 : user.getNoShowCount()) + 1;
            user.setNoShowCount(noShow);
            userRepository.save(user);
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("rule", "book.check-in-after-min");
            evidence.put("reserveDate", BizConstants.formatDate(reservation.getReserveDate()));
            evidence.put("startSlot", reservation.getStartSlot());
            evidence.put("slotLabel", BizConstants.slotLabel(reservation.getStartSlot()));
            evidence.put("deadline", BizConstants.formatDateTime(
                    BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot())
                            .plusMinutes(ruleConfigService.checkInAfterMin())));
            evidence.put("judgedAt", BizConstants.formatDateTime(now));
            creditService.applyViolation(user.getId(), ViolationType.NO_SHOW, null,
                    creditService.buildEvidence(evidence), reservation.getId(), null);
        });
        return 1;
    }

    /**
     * 单条置 COMPLETED：归档预约 + 释放 slot。
     *
     * @param reservation 预约
     * @param now         当前时间
     * @return 处理条数（固定 1，便于调用方累加计数）
     */
    @Transactional
    public int markCompleted(Reservation reservation, LocalDateTime now) {
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservation.setCancelTime(now);
        reservationRepository.save(reservation);
        reservationSlotRepository.deleteByReservationId(reservation.getId());
        return 1;
    }
}

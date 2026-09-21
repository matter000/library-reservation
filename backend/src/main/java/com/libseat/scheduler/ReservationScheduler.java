package com.libseat.scheduler;

import com.libseat.common.BizConstants;
import com.libseat.entity.Reservation;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.ReservationRepository;
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
 *   <li>每 5 分钟：CHECKED_IN 且「结束 &lt; 现在」→ COMPLETED（删 slot）。</li>
 * </ol>
 *
 * <p>本类只负责「扫描 + 判定」，落库写操作交给 {@link ReservationStateService}，
 * 以保证单条记录的「状态归档」与「slot 释放」在同一事务内完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private final ReservationRepository reservationRepository;

    private final ReservationStateService reservationStateService;

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
        int count = 0;
        for (Reservation reservation : candidates) {
            LocalDateTime start = BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot());
            if (!start.plusMinutes(BizConstants.CHECK_IN_AFTER_MIN).isBefore(now)) {
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
}

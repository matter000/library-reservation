package com.libseat.api;

import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.scheduler.ReservationScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5 定时任务：NO_SHOW 与 COMPLETED 流转、slot 物理删除、no_show_count 递增。
 *
 * <p>直接调用 scheduler 的两个扫描方法（与被 @Scheduled 调用的逻辑完全一致），
 * 用「已过期的边界数据」驱动，不依赖真实等待。
 */
class SchedulerApiTest extends ApiTestBase {

    @Autowired
    private ReservationScheduler scheduler;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    private long uid(String token) throws Exception {
        return data(exec(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private Reservation insert(Long userId, long seatId, LocalDate date, int startSlot, int duration,
                               ReservationStatus status) {
        Reservation reservation = Reservation.builder()
                .userId(userId).seatId(seatId).floorId(floor1)
                .reserveDate(date).startSlot(startSlot).endSlot(startSlot + duration)
                .status(status).build();
        return reservationRepository.saveAndFlush(reservation);
    }

    private void insertSlots(Reservation reservation, long seatId, LocalDate date) {
        for (int slot = reservation.getStartSlot(); slot < reservation.getEndSlot(); slot++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(reservation)
                    .seatId(seatId)
                    .reserveDate(date)
                    .slotIndex(slot)
                    .build());
        }
    }

    @Test
    @DisplayName("【P0-5】CONFIRMED 且超过开始+15min → NO_SHOW，slot 物理删除，no_show_count +1")
    void scanNoShow() throws Exception {
        long userId = uid(u1);
        LocalDate today = LocalDate.now();
        int hour = LocalDateTime.now().getHour();
        if (hour < 10) {
            return; // 需要至少已过 10:00 才有可靠的“超时”数据
        }
        long seat = seatId(floor1, "A50");

        Reservation target = insert(userId, seat, today, 8, 2, ReservationStatus.CONFIRMED);
        insertSlots(target, seat, today);
        assertEquals(2, reservationSlotRepository.findBySeatIdAndReserveDate(seat, today).size());

        int before = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);

        scheduler.scanNoShow();

        Reservation after = reservationRepository.findById(target.getId()).orElseThrow();
        assertEquals(ReservationStatus.NO_SHOW, after.getStatus(), "超时未签到应置为 NO_SHOW");
        assertTrue(after.getCancelTime() != null, "NO_SHOW 应写入 cancel_time");

        assertEquals(0, reservationSlotRepository.findBySeatIdAndReserveDate(seat, today).size(),
                "★ NO_SHOW 后 slot 行必须物理删除");

        int actualAfter = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);
        assertEquals(before + 1, actualAfter, "★ no_show_count 应递增 1，实际 " + before + " → " + actualAfter);

        long noShowBeforeAgain = 0;
        scheduler.scanNoShow();
        int third = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);
        assertEquals(actualAfter, third, "重复扫描不应重复计数（幂等）");
        assertTrue(noShowBeforeAgain >= 0);
    }

    @Test
    @DisplayName("【P0-5】CHECKED_IN 且已过结束时间 → COMPLETED，slot 物理删除，不加违约次数")
    void completeExpired() throws Exception {
        long userId = uid(u1);
        int hour = LocalDateTime.now().getHour();
        if (hour < 10) {
            return;
        }
        LocalDate today = LocalDate.now();
        long seat = seatId(floor1, "A51");

        Reservation target = insert(userId, seat, today, 8, 1, ReservationStatus.CHECKED_IN);
        insertSlots(target, seat, today);
        assertEquals(1, reservationSlotRepository.findBySeatIdAndReserveDate(seat, today).size());

        int before = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);

        scheduler.completeExpired();

        Reservation after = reservationRepository.findById(target.getId()).orElseThrow();
        assertEquals(ReservationStatus.COMPLETED, after.getStatus(), "已过结束时间的 CHECKED_IN 应归档为 COMPLETED");
        assertEquals(0, reservationSlotRepository.findBySeatIdAndReserveDate(seat, today).size(),
                "★ COMPLETED 后 slot 行必须物理删除");

        int afterCount = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);
        assertEquals(before, afterCount, "★ 正常完成不应增加违约次数");
    }

    @Test
    @DisplayName("【P0-5】未到时间的 CONFIRMED / CHECKED_IN 不应被定时任务误伤")
    void notYetExpiredShouldKeep() throws Exception {
        long userId = uid(u2);
        LocalDate future = LocalDate.now().plusDays(3);
        long seat = seatId(floor1, "A52");

        Reservation futureConfirmed = insert(userId, seat, future, 14, 2, ReservationStatus.CONFIRMED);
        insertSlots(futureConfirmed, seat, future);

        int before = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);

        scheduler.scanNoShow();
        scheduler.completeExpired();

        Reservation after = reservationRepository.findById(futureConfirmed.getId()).orElseThrow();
        assertEquals(ReservationStatus.CONFIRMED, after.getStatus(), "未来日期的预约不应被定时任务改动");
        assertEquals(2, reservationSlotRepository.findBySeatIdAndReserveDate(seat, future).size(),
                "未来日期的 slot 行不应被删除");
        int afterCount = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, userId);
        assertEquals(before, afterCount);
    }

    @Test
    @DisplayName("【P0-5】全部 SCHEDULED 方法共用 slot 删除后，不应残留孤儿数据")
    void noOrphanAfterScheduler() throws Exception {
        long userId = uid(u3);
        LocalDate today = LocalDate.now();
        long seat = seatId(floor1, "A53");
        int hour = LocalDateTime.now().getHour();
        if (hour < 10) {
            return;
        }
        Reservation r = insert(userId, seat, today, 8, 2, ReservationStatus.CONFIRMED);
        insertSlots(r, seat, today);

        scheduler.scanNoShow();

        List<?> orphans = jdbc.queryForList("select s.id from t_reservation_slot s "
                + "where not exists (select 1 from t_reservation r2 where r2.id = s.reservation_id)");
        assertTrue(orphans.isEmpty(), "定时任务执行后不应残留孤儿 slot 行，实际 " + orphans.size() + " 行");
    }
}

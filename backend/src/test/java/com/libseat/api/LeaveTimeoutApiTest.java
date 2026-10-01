package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.scheduler.ReservationScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * V2 S2 / T-V2-11：暂离超时扫描与释放（DESIGN §5.3）。
 *
 * <p>★ 这里最要紧的不是「扫到了」，而是三件 V1 踩过的坑：
 * ① 一条脏数据不能中断整轮；② 状态归档与 slot 释放必须同事务（否则座位永久占着且不报错）；
 * ③ 重复扫描不能重复计数。测试按这三条各写一例。
 *
 * <p>造数一律直插 CHECKED_IN 预约（真实签到窗口只有前后 15 分钟），
 * 并按人隔离：扫描是全库级别的，别的用例残留的 OPEN 暂离也会被一起处理。
 */
class LeaveTimeoutApiTest extends ApiTestBase {

    @Autowired
    private ReservationScheduler scheduler;

    @Autowired
    private LeaveRecordRepository leaveRecordRepository;

    /** ★ 用 Spy 在「slot 删除」这一步制造真实失败，验证整条回滚（不是只验有没有 try-catch）。 */
    @SpyBean
    private ReservationSlotRepository slotRepositorySpy;

    private long userId(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    /**
     * 取在 [startSlot, startSlot+slotCount) 全时段都空闲的座位。
     *
     * <p>★ 只看起始格会出错：下面的 {@code overdueLeave} 是直插 slot 明细行的，
     * 起始格空闲但后面某格被占（种子数据或其他用例的预约）就会撞
     * {@code uk_slot_seat_date_idx}，表现为与本用例无关的偶发失败。
     */
    private long freeSeat(long floorId, String date, int startSlot, int slotCount) throws Exception {
        java.util.Set<Long> candidates = new java.util.LinkedHashSet<>(freeSeatsAt(floorId, date, startSlot));
        for (int i = 1; i < slotCount; i++) {
            candidates.retainAll(freeSeatsAt(floorId, date, startSlot + i));
        }
        assertTrue(!candidates.isEmpty(), date + " 时段 " + startSlot + ".." + (startSlot + slotCount)
                + " 没有全时段空闲的座位");
        return new java.util.ArrayList<>(candidates).get(0);
    }

    private java.util.Set<Long> freeSeatsAt(long floorId, String date, int slot) throws Exception {
        JsonNode map = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1))));
        java.util.Set<Long> free = new java.util.LinkedHashSet<>();
        for (JsonNode seat : map.path("seats")) {
            if ("FREE".equals(seat.path("displayStatus").asText())) {
                free.add(seat.path("seatId").asLong());
            }
        }
        return free;
    }

    /**
     * 造一条「已签到 + 暂离已超期」的完整现场。
     *
     * @param overdueMin 应归时刻与现在的差值分钟（负数即已超期）
     */
    private LeaveRecord overdueLeave(long uid, long floorId, LocalDate date,
                                     int startSlot, int slotCount, int overdueMin) throws Exception {
        long seat = freeSeat(floorId, date.toString(), startSlot, slotCount);
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(uid).seatId(seat).floorId(floorId).reserveDate(date)
                .startSlot(startSlot).endSlot(startSlot + slotCount)
                .status(ReservationStatus.CHECKED_IN)
                .checkInTime(BizConstants.slotStart(date, startSlot)).build());
        for (int i = 0; i < slotCount; i++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(reservation).seatId(seat).reserveDate(date)
                    .slotIndex(startSlot + i).build());
        }
        LocalDateTime planned = LocalDateTime.now().plusMinutes(overdueMin);
        return leaveRecordRepository.saveAndFlush(LeaveRecord.builder()
                .reservationId(reservation.getId()).userId(uid).seatId(seat)
                .reasonCode("MEAL").startTime(planned.minusMinutes(60)).plannedEndTime(planned)
                .status(LeaveStatus.OPEN).openFlag(LeaveStatus.OPEN_FLAG).build());
    }

    /** 当前所处的 30 分钟格。 */
    private int nowSlot() {
        return BizConstants.slotOf(LocalTime.now());
    }

    private int noShowCount(long uid) {
        Integer n = jdbc.queryForObject("select no_show_count from t_user where id = ?", Integer.class, uid);
        return n == null ? 0 : n;
    }

    private int slotRows(long reservationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_reservation_slot WHERE reservation_id = ?", Integer.class, reservationId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("【验收1】超期 OPEN 暂离 → TIMEOUT + 预约 VIOLATED + slot 清零 + 平面图回 FREE")
    void overdueLeaveReleased() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int start = nowSlot();
        LeaveRecord record = overdueLeave(uid, floor1, today, start, 4, -1);
        Reservation reservation = reservationRepository.findById(record.getReservationId()).orElseThrow();
        int noShowBefore = noShowCount(uid);

        scheduler.scanLeaveTimeout();

        LeaveRecord after = leaveRecordRepository.findById(record.getId()).orElseThrow();
        assertEquals(LeaveStatus.TIMEOUT, after.getStatus(), "暂离应记为超时");
        assertNull(after.getOpenFlag(), "★ open_flag 必须归 NULL，否则该预约再也开不了新暂离");
        assertEquals(ReservationStatus.VIOLATED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus(),
                "预约应归档为暂离未归");
        assertEquals(0, slotRows(reservation.getId()), "slot 行必须物理删除");
        assertEquals("FREE", displayStatus(floor1, reservation.getSeatId(), today.toString(), start),
                "座位必须真的还回平面图，否则就是「已归档但仍占座」");
        assertEquals(noShowBefore + 1, noShowCount(uid), "S3 之前先用 V1 的违约计数字段兜住");

        // 归档后必须落在「已结束」分组里，否则用户在页面上再也找不到这条记录
        JsonNode finished = data(exec(get("/api/reservations/my")
                .param("tab", "finished").param("page", "1").param("size", "50")
                .header("Authorization", bearer(me))));
        boolean listed = false;
        for (JsonNode node : finished.path("list")) {
            if (node.path("reservationId").asLong() == reservation.getId()) {
                listed = true;
                assertEquals("暂离未归", node.path("statusText").asText(), "文案要说清成因，不能与「未签到」混为一谈");
            }
        }
        assertTrue(listed, "VIOLATED 应归入 finished 分组，实际列表 " + finished.path("list"));
    }

    @Test
    @DisplayName("【验收2】脏数据（关联预约缺失）只记 error，不中断整轮")
    void dirtyRecordDoesNotBreakRound() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();

        // 脏数据：暂离指向一条不存在的预约
        LocalDateTime planned = LocalDateTime.now().minusMinutes(1);
        LeaveRecord orphan = leaveRecordRepository.saveAndFlush(LeaveRecord.builder()
                .reservationId(9_900_000L + uid).userId(uid)
                .seatId(seatId(floor1, "A01"))
                .reasonCode("MEAL").startTime(planned.minusMinutes(60)).plannedEndTime(planned)
                .status(LeaveStatus.OPEN).openFlag(LeaveStatus.OPEN_FLAG).build());

        // 同轮的正常工作记录：放明天的 16 格，避开今天各种扫描的干扰
        LeaveRecord normal = overdueLeave(uid, floor1, today.plusDays(1), 16, 4, -1);
        Reservation normalReservation = reservationRepository.findById(normal.getReservationId()).orElseThrow();

        scheduler.scanLeaveTimeout();

        LeaveRecord orphanAfter = leaveRecordRepository.findById(orphan.getId()).orElseThrow();
        assertEquals(LeaveStatus.TIMEOUT, orphanAfter.getStatus(), "脏记录本身也要结掉");
        assertNull(orphanAfter.getOpenFlag(), "名额不释放的话它会永远卡在扫描队列里");
        assertEquals(ReservationStatus.VIOLATED,
                reservationRepository.findById(normalReservation.getId()).orElseThrow().getStatus(),
                "★ 一条脏数据不得挡住后面的正常记录");
        assertEquals(0, slotRows(normalReservation.getId()), "正常记录的座位必须被释放");
    }

    @Test
    @DisplayName("【验收3】slot 删除失败 → 整条回滚，不得出现「已归档但仍占座」")
    void slotDeleteFailureRollsBackWholeUnit() throws Exception {
        String me = newUser();
        long uid = userId(me);
        int start = nowSlot();
        LeaveRecord record = overdueLeave(uid, floor1, LocalDate.now(), start, 4, -1);
        Reservation reservation = reservationRepository.findById(record.getReservationId()).orElseThrow();
        int noShowBefore = noShowCount(uid);

        doThrow(new RuntimeException("模拟 slot 删除失败"))
                .when(slotRepositorySpy).deleteByReservationId(anyLong());
        try {
            scheduler.scanLeaveTimeout();
        } finally {
            reset(slotRepositorySpy);
        }

        assertEquals(ReservationStatus.CHECKED_IN,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus(),
                "★ 状态归档必须跟着回滚，不能只回滚 slot");
        assertEquals(LeaveStatus.OPEN, leaveRecordRepository.findById(record.getId()).orElseThrow().getStatus(),
                "暂离状态也要回滚，否则会出现「预约仍占用但暂离已终结」");
        assertEquals(4, slotRows(reservation.getId()), "slot 行应保持原样");
        assertEquals(noShowBefore, noShowCount(uid), "违约计数不得在回滚的轮次里累加");

        // 恢复后重跑：证明刚才那次失败没把数据做坏，下一轮仍能正常处理
        scheduler.scanLeaveTimeout();
        assertEquals(ReservationStatus.VIOLATED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertEquals(0, slotRows(reservation.getId()));
        assertEquals(noShowBefore + 1, noShowCount(uid));
    }

    @Test
    @DisplayName("【验收4】重复扫描幂等，不重复计数")
    void repeatedScanIsIdempotent() throws Exception {
        String me = newUser();
        long uid = userId(me);
        int start = nowSlot();
        LeaveRecord record = overdueLeave(uid, floor1, LocalDate.now(), start, 4, -1);
        Reservation reservation = reservationRepository.findById(record.getReservationId()).orElseThrow();

        scheduler.scanLeaveTimeout();
        int afterFirst = noShowCount(uid);
        scheduler.scanLeaveTimeout();
        scheduler.scanLeaveTimeout();

        assertEquals(afterFirst, noShowCount(uid), "★ 第二三轮不得再累加违约次数");
        assertEquals(LeaveStatus.TIMEOUT, leaveRecordRepository.findById(record.getId()).orElseThrow().getStatus());
        assertEquals(ReservationStatus.VIOLATED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
    }
}

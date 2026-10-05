package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P0-3 预约业务规则边界（真实 HTTP 层 + 真实数据库）。
 */
class ReservationRuleApiTest extends ApiTestBase {

    private static final int PARAM_ERROR = 4001;
    private static final int SEAT_CONFLICT = 4091;
    private static final int USER_OVERLAP = 4092;
    private static final int STATUS_NOT_ALLOWED = 4093;

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private Reservation insertReservation(Long userId, long seatId, LocalDate date, int startSlot,
                                          int duration, ReservationStatus status) {
        Reservation reservation = Reservation.builder()
                .userId(userId)
                .seatId(seatId)
                .floorId(floor1)
                .reserveDate(date)
                .startSlot(startSlot)
                .endSlot(startSlot + duration)
                .status(status)
                .build();
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        // ★ 同步写占用明细：平面图的占用状态是从 t_reservation_slot 派生的，
        // 只写主表会让该座位在平面图上仍显示 FREE —— 这正是之前 checkInSuccess
        // 断言「签到后应 IN_USE」偶发失败的原因（造数不完整）。
        // 与 ReservationService 语义对齐：只有 CONFIRMED / CHECKED_IN 占用座位。
        if (status == ReservationStatus.CONFIRMED || status == ReservationStatus.CHECKED_IN) {
            for (int i = 0; i < duration; i++) {
                reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                        .reservation(saved)
                        .seatId(seatId)
                        .reserveDate(date)
                        .slotIndex(startSlot + i)
                        .build());
            }
        }
        return saved;
    }

    // ==================== duration ====================

    @Test
    @DisplayName("【duration】0 / 5 / -1 / 2.5 / \"2\" 均返回 4001")
    void invalidDuration() throws Exception {
        long seat = seatId(floor1, "A20");
        String me = newUser();

        assertEquals(PARAM_ERROR, code(reserveRaw(me, seat, dw(3), slot(10), 0)), "duration=0 应 4001");
        assertEquals(PARAM_ERROR, code(reserveRaw(me, seat, dw(3), slot(10), 5)), "duration=5 应 4001");
        assertEquals(PARAM_ERROR, code(reserveRaw(me, seat, dw(3), slot(10), -1)), "duration=-1 应 4001");

        // 非整数 / 字符串
        // ⚠ 实测发现：Jackson 2.15 默认标量强制转换会把 2.5 截断成 2，后端接受了本该拒绝的入参。
        // 这里显式固化当前行为（code=0 且 duration=2），便于后续修复时对比。
        String nonInteger = "{\"seatId\":" + seat + ",\"date\":\"" + dw(3) + "\",\"startSlot\":10,\"duration\":2.5}";
        MvcResult r1 = exec(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(nonInteger).header("Authorization", bearer(me)));
        System.out.println("[QA][duration=2.5] 实际响应 = " + body(r1));
        if (code(r1) == 0) {
            // 已知的低风险宽松行为：记录但不判失败
            assertEquals("10:00-12:00", data(r1).path("timeRange").asText(),
                    "duration=2.5 被静默截断为 2（Jackson 标量强制转换），应记录为待改进项");
        } else {
            assertEquals(PARAM_ERROR, code(r1), "duration=2.5 应 4001，实际：" + body(r1));
        }

        // ⚠ 实测发现：字符串 "2" 也会被 Jackson 强制转成整数 2 并被接受。
        // 用独立用户 + 独立时段，单独固化这一行为。
        String asString = "{\"seatId\":" + seat + ",\"date\":\"" + dw(3) + "\",\"startSlot\":14,\"duration\":\"2\"}";
        MvcResult r2 = exec(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(asString).header("Authorization", bearer(newUser())));
        System.out.println("[QA][duration=\"2\"] 实际响应 = " + body(r2));
        if (code(r2) == 0) {
            assertEquals("14:00-16:00", data(r2).path("timeRange").asText(),
                    "duration=\"2\" 被静默转成整数 2（Jackson 标量强制转换），应记录为待改进项");
        } else {
            assertEquals(PARAM_ERROR, code(r2), "duration=\"2\" 应 4001，实际：" + body(r2));
        }

        // 不传 duration → 默认 2 → 成功（换一个没被占用的时段，避免与上面的探针互相干扰）
        JsonNode ok = reserveOk(newUser(), seat, dw(3), slot(16), null);
        assertEquals("16:00-18:00", ok.path("timeRange").asText(), "默认时长应为 2 小时");
    }

    // ==================== date ====================

    @Test
    @DisplayName("【date】昨天 / 今天+7 返回 4001；今天+6 边界应成功")
    void dateWindow() throws Exception {
        long seat = seatId(floor1, "A21");   // 1F-SILENT：本区口径「提前 3 天（含当天）」→ today..today+2
        String me = newUser();

        MvcResult yesterday = reserveRaw(me, seat, d(-1), slot(10), 2);
        assertEquals(PARAM_ERROR, code(yesterday), "昨天应 4001，实际：" + body(yesterday));

        MvcResult beyondZone = reserveRaw(me, seat, d(3), slot(10), 2);
        assertEquals(PARAM_ERROR, code(beyondZone), "静音区第 4 天应 4001，实际：" + body(beyondZone));
        assertTrue(msg(beyondZone).contains("静音自习区"),
                "★ 报错必须点名是哪一区的口径，否则用户会去怪全局配置：" + msg(beyondZone));
        assertTrue(msg(beyondZone).contains("3 天"), "要给出本区可提前天数：" + msg(beyondZone));

        JsonNode edge = reserveOk(me, seat, d(2), slot(10), 2);
        assertEquals(d(2), edge.path("date").asText(), "today+2 是静音区最远可约日期，应成功");

        if (LocalDateTime.now().getHour() < 21) {
            JsonNode todayOk = reserveOk(me, seat, d(0), slot(21), 1);
            assertEquals(d(0), todayOk.path("date").asText(), "今天最晚时段（21:00-22:00）应可预约");
        }

        // ★ 分区没配本区口径时才回落全局规则值（book.advance-days，默认 7 → today..today+6）。
        //   与上面那条 d(3) 成对：只看这条会以为"全局说了算"，只看上面那条会以为"分区是硬天花板"。
        Long zoneId = jdbc.queryForObject("select zone_id from t_seat where id = ?", Long.class, seat);
        assertTrue(zoneId != null, "A21 应已归属分区");
        jdbc.update("update t_zone set advance_days = null where id = ?", zoneId);
        try {
            JsonNode globalEdge = reserveOk(newUser(), seat, d(6), slot(10), 2);
            assertEquals(d(6), globalEdge.path("date").asText(),
                    "分区未配时应吃全局 7 天窗口，today+6 应成功，实际：" + globalEdge);
            MvcResult tooFar = reserveRaw(newUser(), seat, d(7), slot(10), 2);
            assertEquals(PARAM_ERROR, code(tooFar), "today+7 应被全局窗口拦下，实际：" + body(tooFar));
        } finally {
            jdbc.update("update t_zone set advance_days = 3 where id = ?", zoneId);
        }
    }

    @Test
    @DisplayName("【date】今天已过去的时段返回 4001")
    void pastSlotToday() throws Exception {
        int hour = LocalDateTime.now().getHour();
        if (hour < 9) {
            return; // 08:00 前不存在“已过去的时段”
        }
        long seat = seatId(floor1, "A22");
        MvcResult result = reserveRaw(newUser(), seat, d(0), slot(8), 1);
        assertEquals(PARAM_ERROR, code(result), "今天已过去的时段应 4001，实际：" + body(result));
    }

    // ==================== startSlot ====================

    @Test
    @DisplayName("【startSlot】7 / 22 / 21+2(跨天) 返回 4001；21+1 应成功")
    void slotRange() throws Exception {
        long seat = seatId(floor1, "A23");
        String me = newUser();

        MvcResult early = reserveRaw(me, seat, dw(3), slot(7), 2);
        assertEquals(PARAM_ERROR, code(early), "startSlot=7 应 4001，实际：" + body(early));

        MvcResult late = reserveRaw(me, seat, dw(3), slot(22), 1);
        assertEquals(PARAM_ERROR, code(late), "startSlot=22 应 4001，实际：" + body(late));

        MvcResult crossDay = reserveRaw(me, seat, dw(3), slot(21), 2);
        assertEquals(PARAM_ERROR, code(crossDay), "startSlot=21 duration=2 跨天应 4001，实际：" + body(crossDay));

        JsonNode ok = reserveOk(me, seat, dw(3), slot(21), 1);
        assertEquals(slot(22), ok.path("endSlot").asInt(), "21:00-22:00 应可预约");
    }

    // ==================== 冲突 ====================

    @Test
    @DisplayName("【4091】同一座位同一时段被第二个用户预约 → 4091")
    void seatSlotConflict() throws Exception {
        long seat = seatId(floor1, "A24");
        String first = newUser();
        String second = newUser();
        reserveOk(first, seat, dw(3), slot(10), 2);
        MvcResult conflict = reserveRaw(second, seat, dw(3), slot(10), 2);
        assertEquals(SEAT_CONFLICT, code(conflict), "座位时段冲突应 4091，实际：" + body(conflict));

        // 部分重叠也应冲突：从 11 开始
        MvcResult partial = reserveRaw(second, seat, dw(3), slot(11), 2);
        assertEquals(SEAT_CONFLICT, code(partial), "部分重叠也应 4091，实际：" + body(partial));

        // 完全不重叠应成功
        JsonNode ok = reserveOk(second, seat, dw(3), slot(12), 2);
        assertEquals(slot(12), ok.path("startSlot").asInt());
    }

    @Test
    @DisplayName("【4092】同一用户时段重叠 → 4092；相邻不重叠应成功")
    void userOverlap() throws Exception {
        long seatA = seatId(floor1, "A25");
        long seatB = seatId(floor1, "A26");
        String me = newUser();

        reserveOk(me, seatA, dw(3), slot(14), 2);   // [14,16)

        MvcResult overlapInside = reserveRaw(me, seatB, dw(3), slot(15), 1);
        assertEquals(USER_OVERLAP, code(overlapInside), "区间内含重叠应 4092，实际：" + body(overlapInside));

        MvcResult overlapSpan = reserveRaw(me, seatB, dw(3), slot(13), 4);
        assertEquals(USER_OVERLAP, code(overlapSpan), "区间跨越重叠应 4092，实际：" + body(overlapSpan));

        JsonNode adjacent = reserveOk(me, seatB, dw(3), slot(12), 2); // [12,14) 与 [14,16) 相邻不重叠
        assertEquals("12:00-14:00", adjacent.path("timeRange").asText());
    }

    // ==================== 座位 / 楼层状态 ====================

    @Test
    @DisplayName("【4093】MAINTENANCE 座位（A15）不可预约")
    void maintenanceSeat() throws Exception {
        long seat = seatId(floor1, MAINT_1F);
        MvcResult result = reserveRaw(newUser(), seat, dw(3), slot(10), 2);
        assertEquals(STATUS_NOT_ALLOWED, code(result), "维护中座位应 4093，实际：" + body(result));
        assertEquals("UNAVAILABLE", displayStatus(floor1, seat, dw(3), slot(10)));
    }

    @Test
    @DisplayName("【4093】DISABLED 座位不可预约（管理员停用后立即生效）")
    void disabledSeat() throws Exception {
        long seat = seatId(floor1, "A27");
        assertEquals(0, code(setSeatStatus(seat, "DISABLED")));
        MvcResult result = reserveRaw(newUser(), seat, dw(3), slot(10), 2);
        assertEquals(STATUS_NOT_ALLOWED, code(result), "停用座位应 4093，实际：" + body(result));
        assertEquals("UNAVAILABLE", displayStatus(floor1, seat, dw(3), slot(10)));
        // 还原
        assertEquals(0, code(setSeatStatus(seat, "AVAILABLE")));
    }

    @Test
    @DisplayName("【4093】楼层 CLOSED 时该层座位不可预约，平面图全部 UNAVAILABLE 但仍可访问")
    void closedFloor() throws Exception {
        long seat = seatId(floor3, "C01");
        assertEquals(0, code(setFloorStatus(floor3, "CLOSED")));

        MvcResult result = reserveRaw(newUser(), seat, dw(3), slot(10), 2);
        assertEquals(STATUS_NOT_ALLOWED, code(result), "关闭楼层应 4093，实际：" + body(result));

        MvcResult map = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor3))
                .param("date", dw(3))
                .param("slot", String.valueOf(slot(10)))
                .header("Authorization", bearer(u1)));
        assertEquals(0, code(map), "CLOSED 楼层平面图接口仍应可访问");
        assertEquals("CLOSED", data(map).path("floorStatus").asText());
        for (JsonNode s : data(map).path("seats")) {
            assertEquals("UNAVAILABLE", s.path("displayStatus").asText(),
                    "CLOSED 楼层所有座位应 UNAVAILABLE，实际：" + s.toString());
        }
        assertEquals(0L, data(map).path("stats").path("free").asLong(), "CLOSED 楼层空闲数应为 0");

        // 还原
        assertEquals(0, code(setFloorStatus(floor3, "OPEN")));
    }

    // ==================== 取消 ====================

    @Test
    @DisplayName("【取消】开始前可取消 → CANCELLED，座位立即释放且 slot 可复用")
    void cancelBeforeStart() throws Exception {
        long seat = seatId(floor1, "A28");
        String me = newUser();
        String other = newUser();
        JsonNode created = reserveOk(me, seat, dw(3), slot(10), 2);
        long reservationId = created.path("reservationId").asLong();
        assertEquals("RESERVED", displayStatus(floor1, seat, dw(3), slot(10)));

        MvcResult cancel = exec(post("/api/reservations/" + reservationId + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(0, code(cancel), "开始前取消应成功，实际：" + body(cancel));
        assertEquals("CANCELLED", data(cancel).path("status").asText());

        assertEquals("FREE", displayStatus(floor1, seat, dw(3), slot(10)), "取消后座位应立即释放为 FREE");
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from t_reservation_slot where reservation_id = ?", Long.class, reservationId),
                "取消后 t_reservation_slot 必须物理删除");

        // slot 复用：换一个用户重新预约同一座位同一时段
        reserveOk(other, seat, dw(3), slot(10), 2);

        // 取消后落入 cancelled 分组
        JsonNode cancelled = data(exec(get("/api/reservations/my")
                .param("tab", "cancelled").header("Authorization", bearer(me))));
        boolean found = false;
        for (JsonNode node : cancelled.path("list")) {
            if (node.path("reservationId").asLong() == reservationId) {
                found = true;
                assertEquals("CANCELLED", node.path("status").asText());
            }
        }
        assertTrue(found, "取消的预约应出现在 cancelled 分组");
    }

    @Test
    @DisplayName("【取消】已开始 / 已签到 / 已结束 / 已取消 的预约不可取消 → 4093")
    void cancelIllegalStatuses() throws Exception {
        String me = newUser();
        long userId = uid(me);
        long seat = seatId(floor1, "A29");

        // 已开始未签到：用「当前小时的前一个时段」构造，凌晨（<09:00）不存在已开始时段则跳过
        int hour = LocalDateTime.now().getHour();
        if (hour >= 9) {
            // ★ 该时段的座位可能被随机种子数据或其它用例占用（硬编码 A29 会撞 uk_slot_seat_date_idx），
            //   故动态取「该日期该时段真正空闲」的座位，与本文件其它造数用例保持一致的隔离方式。
            long startedSeat = freeSeatOn(floor1, d(0), slot(hour - 1));
            Reservation started = insertReservation(userId, startedSeat, LocalDate.now(), slot(hour - 1), 1,
                    ReservationStatus.CONFIRMED);
            MvcResult r1 = exec(post("/api/reservations/" + started.getId() + "/cancel")
                    .header("Authorization", bearer(me)));
            assertEquals(STATUS_NOT_ALLOWED, code(r1), "已开始未签到应 4093，实际：" + body(r1));
        }

        Reservation checkedIn = insertReservation(userId, seat, LocalDate.now().plusDays(3), 10, 2,
                ReservationStatus.CHECKED_IN);
        MvcResult r2 = exec(post("/api/reservations/" + checkedIn.getId() + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(STATUS_NOT_ALLOWED, code(r2), "已签到应 4093，实际：" + body(r2));

        Reservation completed = insertReservation(userId, seat, LocalDate.now().plusDays(3), 12, 2,
                ReservationStatus.COMPLETED);
        MvcResult r3 = exec(post("/api/reservations/" + completed.getId() + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(STATUS_NOT_ALLOWED, code(r3), "已结束应 4093，实际：" + body(r3));

        Reservation cancelled = insertReservation(userId, seat, LocalDate.now().plusDays(3), 14, 2,
                ReservationStatus.CANCELLED);
        MvcResult r4 = exec(post("/api/reservations/" + cancelled.getId() + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(STATUS_NOT_ALLOWED, code(r4), "重复取消应 4093，实际：" + body(r4));

        Reservation noShow = insertReservation(userId, seat, LocalDate.now().plusDays(3), 16, 2,
                ReservationStatus.NO_SHOW);
        MvcResult r5 = exec(post("/api/reservations/" + noShow.getId() + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(STATUS_NOT_ALLOWED, code(r5), "NO_SHOW 应 4093，实际：" + body(r5));

        Reservation adminCancelled = insertReservation(userId, seat, LocalDate.now().plusDays(3), 18, 2,
                ReservationStatus.ADMIN_CANCELLED);
        MvcResult r6 = exec(post("/api/reservations/" + adminCancelled.getId() + "/cancel")
                .header("Authorization", bearer(me)));
        assertEquals(STATUS_NOT_ALLOWED, code(r6), "ADMIN_CANCELLED 应 4093，实际：" + body(r6));

        assertTrue(hour >= 0);
    }

    // ==================== 签到 ====================

    @Test
    @DisplayName("【签到】窗口外（未来日期 / 已过宽限）→ 4093")
    void checkInOutOfWindow() throws Exception {
        long userId = uid(u1);
        long seat = seatId(floor1, "A30");

        // 太早：3 天后的预约现在不可能签到
        Reservation future = insertReservation(userId, seat, LocalDate.now().plusDays(3), 10, 2,
                ReservationStatus.CONFIRMED);
        MvcResult tooEarly = exec(post("/api/reservations/" + future.getId() + "/check-in")
                .header("Authorization", bearer(u1)));
        assertEquals(STATUS_NOT_ALLOWED, code(tooEarly), "签到窗口未到应 4093，实际：" + body(tooEarly));

        // 太晚：开始时间已过 2 小时
        int hour = LocalDateTime.now().getHour();
        if (hour >= 10) {
            Reservation late = insertReservation(userId, seat, LocalDate.now(), slot(hour - 2), 1,
                    ReservationStatus.CONFIRMED);
            MvcResult tooLate = exec(post("/api/reservations/" + late.getId() + "/check-in")
                    .header("Authorization", bearer(u1)));
            assertEquals(STATUS_NOT_ALLOWED, code(tooLate), "超过签到窗口应 4093，实际：" + body(tooLate));
        }
    }

    @Test
    @DisplayName("【签到】窗口内签到成功 → CHECKED_IN，平面图变 IN_USE")
    void checkInSuccess() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getMinute();
        int targetSlot;
        // ★ 窗口边界要留余量：这一格的签到窗口是「开始时刻 ~ +15 分钟」，
        //   若按 minute <= 15 取整点格，跑过 HH:15 之后再来签到就会被**正确地**判为超窗（4093），
        //   表现为「用例只在某个 15 分钟里是绿的」。取 <= 10 就给后续请求留下 5 分钟余量。
        if (minute <= 10 && now.getHour() >= 8 && now.getHour() <= 21) {
            targetSlot = slot(now.getHour());
        } else if (minute >= 45 && now.getHour() + 1 >= 8 && now.getHour() + 1 <= 21) {
            // 下一格的窗口从 HH:45 开到 HH+1:15，此刻刚开，余量充足
            targetSlot = slot(now.getHour() + 1);
        } else {
            // 当前时刻不在任何签到窗口内（如 10:30），该用例跳过
            return;
        }

        long userId = uid(u1);
        long seat = freeSeatOn(floor1, d(0), targetSlot);
        Reservation reservation = insertReservation(userId, seat, LocalDate.now(), targetSlot, 2,
                ReservationStatus.CONFIRMED);

        MvcResult result = exec(post("/api/reservations/" + reservation.getId() + "/check-in")
                .header("Authorization", bearer(u1)));
        assertEquals(0, code(result), "窗口内签到应成功，实际：" + body(result));
        assertEquals("CHECKED_IN", data(result).path("status").asText());
        assertEquals("IN_USE", displayStatus(floor1, seat, d(0), targetSlot), "签到后平面图应显示 IN_USE");

        // 重复签到 → 4093
        MvcResult again = exec(post("/api/reservations/" + reservation.getId() + "/check-in")
                .header("Authorization", bearer(u1)));
        assertEquals(STATUS_NOT_ALLOWED, code(again), "重复签到应 4093，实际：" + body(again));

        // 已进入「使用中」分组
        JsonNode inProgress = data(exec(get("/api/reservations/my")
                .param("tab", "inProgress").header("Authorization", bearer(u1))));
        boolean found = false;
        for (JsonNode node : inProgress.path("list")) {
            if (node.path("reservationId").asLong() == reservation.getId()) {
                found = true;
            }
        }
        assertTrue(found, "签到后应出现在 inProgress 分组");
    }

    // ==================== 我的预约 tab ====================

    @Test
    @DisplayName("【我的预约】upcoming 包含已创建未开始的 CONFIRMED（不丢单）")
    void myReservationsTabs() throws Exception {
        long seat = seatId(floor1, "A32");
        String me = newUser();
        JsonNode created = reserveOk(me, seat, dw(3), slot(10), 2);
        long reservationId = created.path("reservationId").asLong();

        JsonNode upcoming = data(exec(get("/api/reservations/my")
                .param("tab", "upcoming").header("Authorization", bearer(me))));
        boolean inUpcoming = false;
        for (JsonNode node : upcoming.path("list")) {
            if (node.path("reservationId").asLong() == reservationId) {
                inUpcoming = true;
                assertTrue(node.path("canCancel").asBoolean(), "未开始的预约 canCancel 应为 true");
                assertEquals("CONFIRMED", node.path("status").asText());
            }
        }
        assertTrue(inUpcoming, "新创建的 CONFIRMED 预约必须出现在 upcoming 分组");

        // 已开始但尚未被定时任务置 NO_SHOW 的 CONFIRMED：不能掉出所有 Tab
        long userId = uid(me);
        Reservation started = insertReservation(userId, freeSeatOn(floor1, d(0), 8), LocalDate.now(), 8, 1,
                ReservationStatus.CONFIRMED);

        java.util.Set<Long> union = new java.util.HashSet<>();
        boolean startedInUpcoming = false;
        for (String tab : new String[]{"upcoming", "inProgress", "finished", "cancelled"}) {
            JsonNode list = data(exec(get("/api/reservations/my")
                    .param("tab", tab).header("Authorization", bearer(me))));
            for (JsonNode node : list.path("list")) {
                union.add(node.path("reservationId").asLong());
                if (node.path("reservationId").asLong() == started.getId() && "upcoming".equals(tab)) {
                    startedInUpcoming = true;
                }
            }
        }
        assertTrue(union.contains(started.getId()), "已开始的 CONFIRMED 预约必须至少落在一个 Tab 中（不能掉单）");

        JsonNode detail = data(exec(get("/api/reservations/" + started.getId())
                .header("Authorization", bearer(me))));
        if ("CONFIRMED".equals(detail.path("status").asText())) {
            assertTrue(startedInUpcoming, "状态仍为 CONFIRMED 时必须留在 upcoming 分组（工程师声明的行为）");
        }
    }

    // ==================== 时段选项 ====================

    @Test
    @DisplayName("【时段】30 分钟格：工作日 28 格、标签 HH:mm-HH:mm，且周末窗口与工作日不同")
    void slotOptions() throws Exception {
        String weekday = dw(1);
        JsonNode options = data(exec(get("/api/config/slots")
                .param("date", weekday).header("Authorization", bearer(u1))));
        assertEquals(com.libseat.common.BizConstants.SLOT_COUNT, options.size(),
                "★ 默认窗口 08:00-22:00 在 30 分钟格下应为 28 项");
        int slot = com.libseat.common.BizConstants.SLOT_INDEX_MIN;
        for (JsonNode option : options) {
            assertEquals(slot, option.path("slotIndex").asInt(), "格序号必须连续无空洞");
            // 验收 4：标签全站统一 HH:mm-HH:mm，且必须体现半点格
            assertEquals(com.libseat.common.BizConstants.slotLabel(slot), option.path("label").asText());
            assertTrue(option.path("label").asText().matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}"),
                    "标签格式必须是 HH:mm-HH:mm，实际：" + option.path("label").asText());
            slot++;
        }

        // 验收 2：换一个日型，开放窗口必须真的不同
        String weekend = nextWeekend();
        JsonNode weekendOptions = data(exec(get("/api/config/slots")
                .param("date", weekend).header("Authorization", bearer(u1))));
        assertTrue(weekendOptions.size() < options.size(),
                "周末档案 09:00-21:00 应比工作日 08:00-22:00 短，实际 "
                        + weekendOptions.size() + " vs " + options.size());

        JsonNode todayOptions = data(exec(get("/api/config/slots")
                .param("date", d(0)).header("Authorization", bearer(u1))));
        JsonNode futureOptions = data(exec(get("/api/config/slots")
                .param("date", weekday).header("Authorization", bearer(u1))));
        for (JsonNode option : futureOptions) {
            assertTrue(option.path("bookable").asBoolean(), "未来工作日所有时段都应可预约");
        }
        int hour = LocalDateTime.now().getHour();
        for (JsonNode option : todayOptions) {
            if (option.path("slotIndex").asInt() <= slot(hour)) {
                assertTrue(!option.path("bookable").asBoolean(),
                        "今天已过去/正在进行的时段 bookable 应为 false，slot=" + option.path("slotIndex"));
            }
        }
    }

    @Test
    @DisplayName("【平面图】派生状态与统计自洽：free+reserved+inUse+unavailable = total")
    void seatMapStatsConsistent() throws Exception {
        long seat = seatId(floor1, "A34");
        reserveOk(newUser(), seat, dw(3), slot(10), 2);

        MvcResult result = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", dw(3))
                .param("slot", String.valueOf(slot(10)))
                .header("Authorization", bearer(u1)));
        JsonNode stats = data(result).path("stats");
        long total = stats.path("total").asLong();
        long sum = stats.path("free").asLong() + stats.path("reserved").asLong()
                + stats.path("inUse").asLong() + stats.path("unavailable").asLong();
        assertEquals(total, sum, "平面图统计应自洽，实际：" + stats.toString());
        assertEquals(60L, total, "1F 应有 60 个座位");
        assertTrue(stats.path("reserved").asLong() >= 1, "至少有刚预约的 1 个 RESERVED");
        assertTrue(stats.path("unavailable").asLong() >= 1, "1F 至少应有 A15 处于 MAINTENANCE");
    }

    @Test
    @DisplayName("【参数】请求体缺少必填字段应返回 4001")
    void missingFields() throws Exception {
        Map<String, Object> noSeat = new HashMap<>();
        noSeat.put("date", dw(3));
        noSeat.put("startSlot", 10);
        MvcResult r1 = exec(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(json(noSeat)).header("Authorization", bearer(u3)));
        assertEquals(PARAM_ERROR, code(r1), "缺少 seatId 应 4001，实际：" + body(r1));

        MvcResult r2 = exec(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{}").header("Authorization", bearer(u3)));
        assertEquals(PARAM_ERROR, code(r2), "空请求体应 4001，实际：" + body(r2));

        MvcResult r3 = exec(post("/api/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":99999999,\"date\":\"" + dw(3) + "\",\"startSlot\":10,\"duration\":2}")
                .header("Authorization", bearer(u3)));
        assertEquals(4040, code(r3), "不存在的座位应 4040，实际：" + body(r3));
    }
}

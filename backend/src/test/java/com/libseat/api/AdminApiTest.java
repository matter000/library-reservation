package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * P0-4 管理员功能：楼层 / 座位 CRUD、批量生成、级联删除、强制释放、统计。
 */
class AdminApiTest extends ApiTestBase {

    private static final int PARAM_ERROR = 4001;
    private static final int CONFLICT = 4090;
    private static final int DELETE_FORBIDDEN = 4094;
    private static final int STATUS_NOT_ALLOWED = 4093;

    private String randomCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private JsonNode createFloor(String name, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("code", code);
        body.put("description", "QA 测试楼层");
        body.put("sortOrder", 99);
        MvcResult result = exec(post("/api/admin/floors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result), "新增楼层应成功，实际：" + this.body(result));
        return data(result);
    }

    private JsonNode createSeat(long floorId, String seatNo, int row, int col) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("floorId", floorId);
        body.put("seatNo", seatNo);
        body.put("rowNo", row);
        body.put("colNo", col);
        MvcResult result = exec(post("/api/admin/seats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result), "新增座位应成功，实际：" + this.body(result));
        return data(result);
    }

    private JsonNode batch(long floorId, String prefix, int rows, int cols, int startNo) throws Exception {
        return batch(floorId, prefix, rows, cols, startNo, 1, 1);
    }

    private JsonNode batch(long floorId, String prefix, int rows, int cols, int startNo,
                           int rowFrom, int colFrom) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("floorId", floorId);
        body.put("prefix", prefix);
        body.put("rows", rows);
        body.put("cols", cols);
        body.put("startNo", startNo);
        body.put("rowFrom", rowFrom);
        body.put("colFrom", colFrom);
        MvcResult result = exec(post("/api/admin/seats/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result), "批量生成应成功，实际：" + this.body(result));
        return data(result);
    }

    // ==================== 楼层 CRUD ====================

    @Test
    @DisplayName("【楼层】CRUD 正常，编号重复 → 4090")
    void floorCrud() throws Exception {
        String code = randomCode("Q");
        JsonNode created = createFloor("QA 一层", code);
        long floorId = created.path("floorId").asLong();
        assertEquals("QA 一层", created.path("name").asText());
        assertEquals("OPEN", created.path("status").asText());

        // 列表包含
        JsonNode list = data(exec(get("/api/admin/floors").header("Authorization", bearer(admin))));
        boolean found = false;
        for (JsonNode floor : list) {
            if (floor.path("floorId").asLong() == floorId) {
                found = true;
            }
        }
        assertTrue(found, "新增楼层应出现在管理列表中");

        // 编辑
        Map<String, Object> update = new HashMap<>();
        update.put("name", "QA 一层改名");
        update.put("code", code);
        MvcResult updated = exec(put("/api/admin/floors/" + floorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(update))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(updated), "编辑楼层应成功，实际：" + body(updated));
        assertEquals("QA 一层改名", data(updated).path("name").asText());

        // 编号重复
        MvcResult duplicate = exec(post("/api/admin/floors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "重名层", "code", code)))
                .header("Authorization", bearer(admin)));
        assertEquals(CONFLICT, code(duplicate), "楼层编号重复应 4090，实际：" + body(duplicate));

        // 状态切换
        assertEquals(0, code(setFloorStatus(floorId, "CLOSED")));
        JsonNode afterClose = data(exec(get("/api/admin/floors").header("Authorization", bearer(admin))));
        for (JsonNode floor : afterClose) {
            if (floor.path("floorId").asLong() == floorId) {
                assertEquals("CLOSED", floor.path("status").asText());
            }
        }
        // 非法状态
        MvcResult badStatus = exec(put("/api/admin/floors/" + floorId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", "WHATEVER")))
                .header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(badStatus), "非法楼层状态应 4001，实际：" + body(badStatus));

        // 删除（无预约）
        MvcResult deleted = deleteFloor(floorId);
        assertEquals(0, code(deleted), "无预约楼层应可删除，实际：" + body(deleted));
    }

    // ==================== 座位 CRUD ====================

    @Test
    @DisplayName("【座位】CRUD 正常，编号重复 → 4090，删除无预约座位成功")
    void seatCrud() throws Exception {
        long floorId = createFloor("QA 座位层", randomCode("S")).path("floorId").asLong();

        JsonNode seat = createSeat(floorId, "Z01", 1, 1);
        long seatId = seat.path("seatId").asLong();
        assertEquals("AVAILABLE", seat.path("status").asText());

        MvcResult duplicate = exec(post("/api/admin/seats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("floorId", floorId, "seatNo", "Z01", "rowNo", 2, "colNo", 2)))
                .header("Authorization", bearer(admin)));
        assertEquals(CONFLICT, code(duplicate), "座位编号重复应 4090，实际：" + body(duplicate));

        // 编辑
        Map<String, Object> update = new HashMap<>();
        update.put("floorId", floorId);
        update.put("seatNo", "Z02");
        update.put("rowNo", 3);
        update.put("colNo", 4);
        MvcResult updated = exec(put("/api/admin/seats/" + seatId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(update))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(updated), "编辑座位应成功，实际：" + body(updated));
        assertEquals("Z02", data(updated).path("seatNo").asText());

        // 状态切换
        MvcResult disabled = setSeatStatus(seatId, "DISABLED");
        assertEquals(0, code(disabled));
        assertEquals("DISABLED", data(disabled).path("status").asText());
        MvcResult badStatus = exec(put("/api/admin/seats/" + seatId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", "BROKEN")))
                .header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(badStatus), "非法座位状态应 4001，实际：" + body(badStatus));

        // 删除
        MvcResult deleted = deleteSeat(seatId);
        assertEquals(0, code(deleted), "无未来预约的座位应可删除，实际：" + body(deleted));
        assertEquals(0L, jdbc.queryForObject("select count(*) from t_seat where id = ?", Long.class, seatId));

        deleteFloor(floorId);
    }

    // ==================== 批量生成 ====================

    @Test
    @DisplayName("【批量生成】编号与坐标两类冲突都要跳过并分别列出（V1 只查编号，会叠座）")
    void batchCreate() throws Exception {
        long floorId = createFloor("QA 批量层", randomCode("B")).path("floorId").asLong();

        JsonNode first = batch(floorId, "Z", 1, 3, 1);
        assertEquals(3, first.path("created").asInt(), "首次应生成 3 个");
        assertEquals(0, first.path("skipped").asInt());
        assertEquals(3, first.path("totalRequested").asInt());
        assertTrue(first.path("skippedSeatNos").isEmpty(), "首次不应有跳过");

        JsonNode second = batch(floorId, "Z", 1, 3, 1);
        assertEquals(0, second.path("created").asInt(), "重复生成应全部跳过");
        assertEquals(3, second.path("skipped").asInt());
        List<String> skipped = new ArrayList<>();
        second.path("skippedSeatNos").forEach(node -> skipped.add(node.asText()));
        assertEquals(List.of("Z01", "Z02", "Z03"), skipped, "skippedSeatNos 应列出被跳过的编号");

        // ★ V1 缺陷点：换个起始编号就绕过编号校验，Z04 会被叠在 Z03 的 (1,3) 上。
        //   现在坐标校验必须兜住它 —— 期望 created=0 而不是 1。
        JsonNode third = batch(floorId, "Z", 1, 3, 2);
        assertEquals(0, third.path("created").asInt(),
                "Z02/Z03 撞编号、Z04 撞 (1,3) 坐标，一颗都不该生成");
        assertEquals(3, third.path("skipped").asInt());
        List<String> pos = new ArrayList<>();
        third.path("skippedPositions").forEach(node -> pos.add(node.asText()));
        assertEquals(List.of("(1,3)"), pos, "只有 Z04 是纯坐标冲突，应单独列出坐标");

        // 要新增第 4 个座位，必须显式给出未占用的列起点
        JsonNode fourth = batch(floorId, "Z", 1, 3, 2, 1, 4);
        assertEquals(1, fourth.path("created").asInt(), "从第 4 列起应新增 Z04");
        assertEquals(2, fourth.path("skipped").asInt(), "Z02/Z03 仍撞编号");

        assertEquals(4L, jdbc.queryForObject("select count(*) from t_seat where floor_id = ?",
                Long.class, floorId), "楼层最终应有 4 个座位");

        // 行优先编号校验：Z01 在第 1 行
        JsonNode page = data(exec(get("/api/admin/floors/" + floorId + "/seats")
                .param("seatNo", "Z01").header("Authorization", bearer(admin))));
        assertEquals("Z01", page.path("list").get(0).path("seatNo").asText());
        assertEquals(1, page.path("list").get(0).path("rowNo").asInt());
        assertEquals(1, page.path("list").get(0).path("colNo").asInt());

        // 同格不得有第二个座位 —— uk_seat_pos 的不变量
        Long dup = jdbc.queryForObject(
                "select count(*) from (select floor_id, row_no, col_no from t_seat where floor_id = ? "
                        + "group by floor_id, row_no, col_no having count(*) > 1)",
                Long.class, floorId);
        assertEquals(0L, dup, "★ 该楼层不应存在任何重叠坐标");

        deleteFloor(floorId);
    }

    // ==================== 级联删除 ====================

    @Test
    @DisplayName("【4094】存在未来预约的座位 / 楼层禁止删除；释放后可删除且无孤儿数据")
    void deleteForbiddenAndCascade() throws Exception {
        long floorId = createFloor("QA 级联层", randomCode("C")).path("floorId").asLong();
        JsonNode seat = createSeat(floorId, "Z01", 1, 1);
        long seatId = seat.path("seatId").asLong();

        // 制造一条未来预约
        JsonNode reservation = reserveOk(newUser(), seatId, d(3), 10, 2);
        long reservationId = reservation.path("reservationId").asLong();

        MvcResult blockedSeat = deleteSeat(seatId);
        assertEquals(DELETE_FORBIDDEN, code(blockedSeat), "有未来预约的座位应 4094，实际：" + body(blockedSeat));

        MvcResult blockedFloor = deleteFloor(floorId);
        assertEquals(DELETE_FORBIDDEN, code(blockedFloor), "有未来预约的楼层应 4094，实际：" + body(blockedFloor));

        // 强制释放后座位可删
        assertEquals(0, code(release(reservationId, "QA 测试释放")));
        MvcResult seatDeleted = deleteSeat(seatId);
        assertEquals(0, code(seatDeleted), "释放后座位应可删除，实际：" + body(seatDeleted));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from t_reservation_slot where reservation_id = ?", Long.class, reservationId),
                "释放后 slot 行必须物理删除");

        // 楼层级联删除 + 孤儿检查
        JsonNode batchResult = batch(floorId, "Z", 2, 2, 1);
        assertEquals(4, batchResult.path("created").asInt());
        List<Long> seatIds = jdbc.queryForList("select id from t_seat where floor_id = ?", Long.class, floorId);
        assertEquals(4, seatIds.size());
        // 给其中两个座位造历史（已结束）预约，验证级联清理
        long historySeat = seatIds.get(0);
        JsonNode history = reserveOk(newUser(), historySeat, d(3), 14, 2);
        assertEquals(0, code(release(history.path("reservationId").asLong(), "清理历史")));

        MvcResult floorDeleted = deleteFloor(floorId);
        assertEquals(0, code(floorDeleted), "无未来预约的楼层应可删除，实际：" + body(floorDeleted));
        assertEquals(4L, data(floorDeleted).path("deletedSeats").asLong(), "应级联删除 4 个座位");

        assertEquals(0L, jdbc.queryForObject("select count(*) from t_floor where id = ?", Long.class, floorId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from t_seat where floor_id = ?", Long.class, floorId));
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from t_reservation where floor_id = ?", Long.class, floorId));
        for (Long id : seatIds) {
            assertEquals(0L, jdbc.queryForObject(
                    "select count(*) from t_reservation_slot where seat_id = ?", Long.class, id),
                    "楼层删除后不应残留 seat_id=" + id + " 的孤儿 slot 行");
        }
        // 全局孤儿检查：slot 行指向不存在的预约
        Long orphans = jdbc.queryForObject("select count(*) from t_reservation_slot s "
                + "where not exists (select 1 from t_reservation r where r.id = s.reservation_id)", Long.class);
        assertEquals(0L, orphans, "t_reservation_slot 不应存在指向已删除预约的孤儿行");
    }

    // ==================== 强制释放 ====================

    @Test
    @DisplayName("【强制释放】空原因 → 4001；正常释放 → ADMIN_CANCELLED 且座位立即释放")
    void releaseRules() throws Exception {
        long seat = seatId(floor1, "A40");
        String owner = newUser();
        JsonNode created = reserveOk(owner, seat, d(3), 10, 2);
        long reservationId = created.path("reservationId").asLong();

        MvcResult empty = release(reservationId, "");
        assertEquals(PARAM_ERROR, code(empty), "空原因应 4001，实际：" + body(empty));

        MvcResult blank = release(reservationId, "   ");
        assertEquals(PARAM_ERROR, code(blank), "纯空格原因应 4001，实际：" + body(blank));

        String tooLong = "x".repeat(101);
        MvcResult longReason = release(reservationId, tooLong);
        assertEquals(PARAM_ERROR, code(longReason), "超过 100 字应 4001，实际：" + body(longReason));

        MvcResult ok = release(reservationId, "设备检修");
        assertEquals(0, code(ok), "正常释放应成功，实际：" + body(ok));
        assertEquals("ADMIN_CANCELLED", data(ok).path("status").asText());
        assertEquals("FREE", displayStatus(floor1, seat, d(3), 10), "释放后座位应立即变 FREE");
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from t_reservation_slot where reservation_id = ?", Long.class, reservationId));

        // 重复释放 → 4093
        MvcResult again = release(reservationId, "再释放一次");
        assertEquals(STATUS_NOT_ALLOWED, code(again), "重复释放应 4093，实际：" + body(again));

        // 用户端能看到释放原因
        MvcResult detailResult = exec(get("/api/reservations/" + reservationId)
                .header("Authorization", bearer(owner)));
        assertEquals(0, code(detailResult), "预约人本人应能查看详情，实际：" + body(detailResult));
        JsonNode detail = data(detailResult);
        assertEquals("ADMIN_CANCELLED", detail.path("status").asText());
        assertEquals("设备检修", detail.path("adminCancelReason").asText());
    }

    // ==================== 使用情况 ====================

    @Test
    @DisplayName("【使用情况】only=occupied / only=free 过滤与统计自洽")
    void usageFilters() throws Exception {
        long seat = seatId(floor1, "A41");
        reserveOk(newUser(), seat, d(3), 10, 2);

        JsonNode all = data(exec(get("/api/admin/usage")
                .param("floorId", String.valueOf(floor1))
                .param("date", d(3)).param("slot", "10")
                .header("Authorization", bearer(admin))));
        JsonNode summary = all.path("summary");
        assertEquals(60L, summary.path("total").asLong());
        assertEquals(summary.path("total").asLong(),
                summary.path("occupied").asLong() + summary.path("free").asLong()
                        + summary.path("unavailable").asLong(), "使用情况统计应自洽");

        JsonNode occupied = data(exec(get("/api/admin/usage")
                .param("floorId", String.valueOf(floor1))
                .param("date", d(3)).param("slot", "10").param("only", "occupied")
                .header("Authorization", bearer(admin))));
        for (JsonNode row : occupied.path("rows")) {
            String display = row.path("displayStatus").asText();
            assertTrue("RESERVED".equals(display) || "IN_USE".equals(display),
                    "only=occupied 不应出现 " + display);
            assertFalse(row.path("occupant").isNull(), "占用行必须有占用人信息");
        }

        JsonNode free = data(exec(get("/api/admin/usage")
                .param("floorId", String.valueOf(floor1))
                .param("date", d(3)).param("slot", "10").param("only", "free")
                .header("Authorization", bearer(admin))));
        for (JsonNode row : free.path("rows")) {
            assertEquals("FREE", row.path("displayStatus").asText(), "only=free 只应返回 FREE 座位");
        }
        assertEquals(summary.path("free").asLong(), free.path("rows").size(), "空闲行数应等于统计中的 free 数");
    }

    // ==================== 统计 ====================

    @Test
    @DisplayName("【统计】5 个接口字段完整、slot-heat 固定 14 项、trend 缺失日期补 0")
    void statistics() throws Exception {
        JsonNode overview = data(exec(get("/api/admin/statistics/overview")
                .header("Authorization", bearer(admin))));
        for (String field : List.of("todayReservations", "currentOnSite", "checkInRate", "todayNoShow",
                "totalSeats", "currentUsageRate")) {
            assertNotNull(overview.get(field), "概览缺少字段 " + field);
            assertFalse(overview.get(field).isNull(), "概览字段 " + field + " 不应为 null");
        }
        assertTrue(overview.path("totalSeats").asLong() >= 124,
                "座位总数应至少为种子数据的 124 个，实际：" + overview.path("totalSeats").asLong());

        JsonNode trend = data(exec(get("/api/admin/statistics/trend").param("days", "5")
                .header("Authorization", bearer(admin))));
        assertEquals(5, trend.size(), "trend 应返回指定天数的数据");
        java.util.Set<String> dates = new java.util.HashSet<>();
        for (JsonNode node : trend) {
            assertNotNull(node.path("date").asText());
            dates.add(node.path("date").asText());
            assertFalse(node.path("count").isNull(), "缺失日期应补 0，不能为 null");
        }
        assertEquals(5, dates.size(), "trend 日期应连续且不重复");

        JsonNode heat = data(exec(get("/api/admin/statistics/slot-heat")
                .header("Authorization", bearer(admin))));
        assertEquals(14, heat.size(), "slot-heat 固定 14 项");
        int index = 8;
        for (JsonNode node : heat) {
            assertEquals(index++, node.path("slotIndex").asInt());
            assertFalse(node.path("count").isNull(), "slot-heat 无数据时应为 0");
        }

        JsonNode floorUsage = data(exec(get("/api/admin/statistics/floor-usage")
                .param("date", d(3)).param("slot", "10")
                .header("Authorization", bearer(admin))));
        assertTrue(floorUsage.size() >= 3, "楼层利用率应至少包含 3 个楼层，实际：" + floorUsage.size());
        for (JsonNode node : floorUsage) {
            assertNotNull(node.path("floorId"));
            assertNotNull(node.path("name"));
            assertFalse(node.path("usageRate").isNull(), "楼层利用率不应为 null");
        }

        JsonNode distribution = data(exec(get("/api/admin/statistics/status-distribution")
                .header("Authorization", bearer(admin))));
        assertEquals(6, distribution.size(), "状态分布应包含 6 种状态");
        java.util.Set<String> statuses = new java.util.HashSet<>();
        for (JsonNode node : distribution) {
            statuses.add(node.path("status").asText());
        }
        assertEquals(java.util.Set.of("CONFIRMED", "CHECKED_IN", "COMPLETED", "CANCELLED", "NO_SHOW",
                "ADMIN_CANCELLED"), statuses, "状态分布应覆盖全部 6 种状态");
    }

    @Test
    @DisplayName("【统计】不存在的楼层 / 空数据不应报错")
    void statisticsEdge() throws Exception {
        MvcResult notFound = exec(get("/api/admin/usage").param("floorId", "999999")
                .header("Authorization", bearer(admin)));
        assertEquals(4040, code(notFound), "不存在的楼层应 4040，实际：" + body(notFound));

        MvcResult badStatus = exec(get("/api/admin/reservations").param("status", "WHATEVER")
                .header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(badStatus), "非法状态筛选应 4001，实际：" + body(badStatus));

        MvcResult emptyKeyword = exec(get("/api/admin/reservations").param("keyword", "不存在的用户zzz")
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(emptyKeyword), "关键字无匹配不应报错");
        assertEquals(0L, data(emptyKeyword).path("total").asLong(), "无匹配时 total 应为 0");
    }
}

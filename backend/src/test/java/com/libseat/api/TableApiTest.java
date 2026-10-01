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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S1 / T-V2-06：桌位 CRUD 与按桌生成。
 *
 * <p>桌位是组队预约的最小单位，所以「同桌座位必须相邻」和「seat_count 不得与真实座位数脱节」
 * 是本用例锁死的两条不变量。
 */
class TableApiTest extends ApiTestBase {

    private static final int SPACE_IN_USE = 4109;
    private static final int CONFLICT = 4090;
    private static final int PARAM_ERROR = 4001;

    private Long seminarZoneId() throws Exception {
        JsonNode list = data(exec(get("/api/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(admin))));
        for (JsonNode node : list) {
            if ("1F-SEMINAR".equals(node.path("code").asText())) {
                return node.path("zoneId").asLong();
            }
        }
        throw new AssertionError("种子中应有 1F-SEMINAR 研讨协作区");
    }

    private Map<String, Object> tableRequest(Long zoneId, String tableNo, int seatCount) {
        Map<String, Object> req = new HashMap<>();
        req.put("zoneId", zoneId);
        req.put("tableNo", tableNo);
        req.put("seatCount", seatCount);
        req.put("status", "AVAILABLE");
        return req;
    }

    private MvcResult createTable(Map<String, Object> body) throws Exception {
        return exec(post("/api/staff/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
    }

    private JsonNode tables(Long zoneId) throws Exception {
        return data(exec(get("/api/staff/tables").param("zoneId", String.valueOf(zoneId))
                .header("Authorization", bearer(admin))));
    }

    @Test
    @DisplayName("【验收4】桌位面仅馆员与系统管理员可用，学生与教师 403")
    void staffGuard() throws Exception {
        long zoneId = seminarZoneId();
        for (String token : List.of(u1, login("teacher1", "123456"))) {
            assertEquals(403, exec(get("/api/staff/tables").param("zoneId", String.valueOf(zoneId))
                            .header("Authorization", bearer(token))).getResponse().getStatus(),
                    "非馆员角色不得访问桌位面");
        }
        assertEquals(0, code(exec(get("/api/staff/tables").param("zoneId", String.valueOf(zoneId))
                .header("Authorization", bearer(login("librarian1", "123456"))))),
                "馆员应可读取桌位");
    }

    @Test
    @DisplayName("【验收3】按桌生成 4 座 → 同桌同行、列连续、table_id 一致")
    void generateAdjacentSeats() throws Exception {
        long zoneId = seminarZoneId();
        MvcResult created = createTable(tableRequest(zoneId, "T77", 4));
        assertEquals(0, code(created), "新增桌位应成功：" + body(created));
        long tableId = data(created).path("tableId").asLong();
        try {
            assertEquals(false, data(created).path("seatCountMatches").asBoolean(),
                    "新桌声明 4 座、实际 0 座，应报不一致而不是假装就绪");

            JsonNode after = data(exec(post("/api/staff/tables/{id}/seats/generate", tableId)
                    .header("Authorization", bearer(admin))));
            assertEquals(4L, after.path("seats").size(), "应生成 4 个座位");
            assertEquals(true, after.path("seatCountMatches").asBoolean(), "生成后声明值与真实值应一致");

            List<Integer> cols = new ArrayList<>();
            int row = after.path("seats").get(0).path("rowNo").asInt();
            for (JsonNode seat : after.path("seats")) {
                assertEquals(row, seat.path("rowNo").asInt(), "★ 同桌必须同一行，否则整排锁座没有意义");
                cols.add(seat.path("colNo").asInt());
            }
            assertEquals(List.of(1, 2, 3, 4), cols, "列号必须连续");

            // 平面图上这 4 座都要可见且不属于任何其它桌
            JsonNode map = data(exec(get("/api/seats/map").param("floorId", String.valueOf(floor1))
                    .param("date", d(1)).param("slot", String.valueOf(slot(14)))
                    .header("Authorization", bearer(u1))));
            int onMap = 0;
            for (JsonNode seat : map.path("seats")) {
                for (JsonNode mine : after.path("seats")) {
                    if (seat.path("seatId").asLong() == mine.path("seatId").asLong()) {
                        onMap++;
                    }
                }
            }
            assertEquals(4, onMap, "生成的座位应出现在平面图上");
        } finally {
            cleanTable(tableId);
        }
    }

    @Test
    @DisplayName("【守卫】桌位仍挂座位时不得删除；调小 seat_count 低于真实值时拒绝")
    void deleteAndShrinkGuards() throws Exception {
        long zoneId = seminarZoneId();
        long tableId = data(createTable(tableRequest(zoneId, "T78", 2))).path("tableId").asLong();
        try {
            exec(post("/api/staff/tables/{id}/seats/generate", tableId).header("Authorization", bearer(admin)));

            MvcResult denied = exec(delete("/api/staff/tables/{id}", tableId)
                    .header("Authorization", bearer(admin)));
            assertEquals(SPACE_IN_USE, code(denied), "★ 还挂着座位的桌位不得删除：" + body(denied));

            MvcResult shrink = exec(put("/api/staff/tables/{id}", tableId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(tableRequest(zoneId, "T78", 1)))
                    .header("Authorization", bearer(admin)));
            assertEquals(PARAM_ERROR, code(shrink), "声明值不得小于真实座位数");
            assertTrue(msg(shrink).contains("2"), "提示应给出真实座位数，实际：" + msg(shrink));
        } finally {
            cleanTable(tableId);
        }
    }

    @Test
    @DisplayName("【校验】同分区桌号重复 4090")
    void duplicateTableNo() throws Exception {
        long zoneId = seminarZoneId();
        MvcResult first = createTable(tableRequest(zoneId, "T79", 4));
        assertEquals(0, code(first));
        long tableId = data(first).path("tableId").asLong();
        try {
            assertEquals(CONFLICT, code(createTable(tableRequest(zoneId, "T79", 4))),
                    "uk_table_zone_no 必须在服务层给出可读冲突");
        } finally {
            exec(delete("/api/staff/tables/{id}", tableId).header("Authorization", bearer(admin)));
        }
    }

    /** 删除桌位前先把它的座位摘掉，避免给后续用例留下挂空的座位。 */
    private void cleanTable(long tableId) throws Exception {
        JsonNode list = tables(seminarZoneId());
        for (JsonNode table : list) {
            if (table.path("tableId").asLong() != tableId) {
                continue;
            }
            for (JsonNode seat : table.path("seats")) {
                exec(delete("/api/admin/seats/{id}", seat.path("seatId").asLong())
                        .header("Authorization", bearer(admin)));
            }
            exec(delete("/api/staff/tables/{id}", tableId).header("Authorization", bearer(admin)));
        }
    }
}

package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S1 / T-V2-08：平面图分区视图与分区资格执行。
 *
 * <p>三处余量（楼层 / 分区 / 平面图）必须由同一次占用派生得出；
 * 分区可见性与可约性必须在服务端判定，前端隐藏只是体验层。
 */
class ZoneSeatApiTest extends ApiTestBase {

    private static final int NOT_FOUND = 4040;
    private static final int ROLE_NOT_ELIGIBLE = 4031;
    private static final int PARAM_ERROR = 4001;

    private long zoneIdOf(String token, Long floorId, String code) throws Exception {
        JsonNode list = data(exec(get("/api/zones").param("floorId", String.valueOf(floorId))
                .header("Authorization", bearer(token))));
        for (JsonNode node : list) {
            if (code.equals(node.path("code").asText())) {
                return node.path("zoneId").asLong();
            }
        }
        throw new AssertionError("分区列表中未找到 " + code);
    }

    private JsonNode seatMap(Long floorId, String date, int slot, Long zoneId, String token) throws Exception {
        var builder = get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot));
        if (zoneId != null) {
            builder = builder.param("zoneId", String.valueOf(zoneId));
        }
        return data(exec(builder.header("Authorization", bearer(token))));
    }

    @Test
    @DisplayName("【验收1】zoneId 过滤：只返回该分区座位，并带该分区的有效规则")
    void zoneFilterAndRules() throws Exception {
        long zoneId = zoneIdOf(u1, floor1, "1F-SEMINAR");
        JsonNode map = seatMap(floor1, dw(3), slot(10), zoneId, u1);

        assertEquals(zoneId, map.path("zoneId").asLong(), "响应应回显所查分区");
        assertEquals(10, map.path("seats").size(), "研讨协作区在 1F 是第 5 行，应为 10 座");
        for (JsonNode seat : map.path("seats")) {
            assertEquals(zoneId, seat.path("zoneId").asLong(),
                    "★ 过滤后不得混入其他分区座位：" + seat.path("seatNo"));
        }
        JsonNode rules = map.path("zoneRules");
        assertEquals(true, rules.path("groupRequired").asBoolean(), "研讨区应标记仅整组");
        assertEquals(180, rules.path("maxDurationMin").asInt(), "研讨区单次上限 180 分钟");
        assertEquals("研讨协作", rules.path("zoneTypeText").asText());
        assertEquals(true, rules.path("eligible").asBoolean(), "学生对研讨区可约（仅要求整组）");
    }

    @Test
    @DisplayName("【验收3】同一时刻 楼层 / 分区 / 平面图 三处空闲数必须一致")
    void threeCountsAgree() throws Exception {
        String date = dw(3);
        int slot = slot(11);
        JsonNode floors = data(exec(get("/api/floors").param("date", date).param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1))));
        JsonNode firstFloor = null;
        for (JsonNode node : floors) {
            if (node.path("floorId").asLong() == floor1) {
                firstFloor = node;
            }
        }
        assertTrue(firstFloor != null, "楼层列表应含 1F");

        // 逐个分区相加必须等于整层平面图统计
        JsonNode zones = data(exec(get("/api/zones").param("floorId", String.valueOf(floor1))
                .param("date", date).param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1))));
        long sumTotal = 0;
        long sumFree = 0;
        for (JsonNode zone : zones) {
            sumTotal += zone.path("totalSeats").asLong();
            sumFree += zone.path("freeSeats").asLong();
        }
        JsonNode wholeMap = seatMap(floor1, date, slot, null, u1);
        assertEquals(wholeMap.path("stats").path("total").asLong(), sumTotal,
                "★ 分区座位数之和必须等于该楼层总数（多了即座位没归属清楚）");
        assertEquals(wholeMap.path("stats").path("free").asLong(), sumFree,
                "★ 分区空闲数之和必须等于平面图空闲数（同一时刻、同一派生入口）");
        assertEquals(firstFloor.path("freeSeats").asLong(), wholeMap.path("stats").path("free").asLong(),
                "楼层卡与平面图的空闲数必须一致");
    }

    @Test
    @DisplayName("【验收2】学生预约教师专区座位 4031，教师预约成功")
    void teacherZoneEligibilityEnforced() throws Exception {
        long teacherZone = zoneIdOf(login("teacher1", "123456"), floor2, "2F-TEACHER");
        JsonNode map = seatMap(floor2, dw(3), slot(10), teacherZone, login("teacher1", "123456"));
        assertTrue(map.path("seats").size() > 0, "教师应能看到专区座位");
        long seat = map.path("seats").get(0).path("seatId").asLong();

        MvcResult studentDenied = exec(post("/api/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(java.util.Map.of("seatId", seat, "date", dw(3), "startSlot", slot(10), "duration", 1)))
                .header("Authorization", bearer(u1)));
        assertEquals(ROLE_NOT_ELIGIBLE, code(studentDenied),
                "★ 学生直接打接口也必须被拦（前端隐藏入口不算防线）：" + body(studentDenied));
        assertTrue(msg(studentDenied).contains("教师专区"), "应说明是哪个区，实际：" + msg(studentDenied));

        JsonNode ruleView = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor2)).param("date", dw(3))
                .param("slot", String.valueOf(slot(10))).param("zoneId", String.valueOf(teacherZone))
                .header("Authorization", bearer(u1))));
        assertEquals(false, ruleView.path("zoneRules").path("eligible").asBoolean(),
                "学生面查询该分区时应明确标记不可约");
        assertFalse(ruleView.path("zoneRules").path("ineligibleReason").asText().isEmpty(),
                "必须给出不可约原因，否则用户只看到灰块不知为何");
    }

    @Test
    @DisplayName("【分区上限】休闲阅览 60 分钟，学生约 2 小时 → 4001")
    void zoneMaxDurationEnforced() throws Exception {
        long lounge = zoneIdOf(u1, floor1, "1F-LOUNGE");
        JsonNode map = seatMap(floor1, dw(3), slot(10), lounge, u1);
        long seat = map.path("seats").get(0).path("seatId").asLong();
        MvcResult tooLong = exec(post("/api/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(java.util.Map.of("seatId", seat, "date", dw(3), "startSlot", slot(10), "duration", 2)))
                .header("Authorization", bearer(u1)));
        assertEquals(PARAM_ERROR, code(tooLong), "超过分区上限应被拒：" + body(tooLong));
        assertTrue(msg(tooLong).contains("60"), "提示应给出分区上限，实际：" + msg(tooLong));

        assertEquals(0, code(exec(post("/api/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(java.util.Map.of("seatId", seat, "date", dw(3), "startSlot", slot(10), "duration", 1)))
                .header("Authorization", bearer(u1)))), "1 小时在上限内应成功");
    }

    @Test
    @DisplayName("【整组区】研讨协作区散客预约 → 4102，且平面图给出原因")
    void groupOnlyZoneRejectsSolo() throws Exception {
        long seminar = zoneIdOf(u1, floor1, "1F-SEMINAR");
        JsonNode map = seatMap(floor1, dw(3), slot(14), seminar, u1);
        List<Long> free = new ArrayList<>();
        map.path("seats").forEach(s -> {
            if ("FREE".equals(s.path("displayStatus").asText())) {
                free.add(s.path("seatId").asLong());
            }
        });
        assertTrue(free.size() > 0, "研讨区应有空闲座位");
        MvcResult denied = exec(post("/api/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(java.util.Map.of("seatId", free.get(0), "date", dw(3),
                        "startSlot", slot(14), "duration", 1)))
                .header("Authorization", bearer(u1)));
        assertEquals(4102, code(denied),
                "★ group_required 分区必须拒绝散客，等 S4 的组队接口再放开：" + body(denied));
    }

    @Test
    @DisplayName("【越权参数】zoneId 不属于该楼层 → 4001；分区不存在 → 4040")
    void zoneParamGuards() throws Exception {
        long seminar = zoneIdOf(u1, floor1, "1F-SEMINAR");
        MvcResult mismatch = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor2))
                .param("zoneId", String.valueOf(seminar))
                .header("Authorization", bearer(u1)));
        assertEquals(PARAM_ERROR, code(mismatch), "分区与楼层不匹配应被拒");
        MvcResult missing = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("zoneId", "999999")
                .header("Authorization", bearer(u1)));
        assertEquals(NOT_FOUND, code(missing), "不存在的分区应 4040");
    }
}

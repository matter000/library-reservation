package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.repository.SeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S1 / T-V2-05：分区建模。
 *
 * <p>覆盖四条验收标准：无孤儿座位、教师专区对学生面不可见、CRUD 与状态切换与删除守卫、
 * 分区响应不含占用人身份。
 */
class ZoneApiTest extends ApiTestBase {

    private static final int SPACE_IN_USE = 4109;
    private static final int CONFLICT = 4090;
    private static final int PARAM_ERROR = 4001;

    @Autowired
    private SeatRepository seatRepository;

    /**
     * 组一个合法的分区保存请求。
     *
     * @param floorId 楼层
     * @param code    编码
     * @return 请求体
     */
    protected Map<String, Object> zoneRequest(Long floorId, String code) {
        Map<String, Object> req = new HashMap<>();
        req.put("floorId", floorId);
        req.put("name", "测试分区" + code);
        req.put("code", code);
        req.put("zoneType", "SILENT");
        req.put("maxDurationMin", 120);
        req.put("advanceDays", 3);
        req.put("allowDiscuss", "NO");
        req.put("allowLeave", true);
        req.put("leaveMaxMin", 30);
        req.put("leaveDailyLimit", 2);
        req.put("allowGroup", false);
        req.put("groupRequired", false);
        req.put("visibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        req.put("eligibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        req.put("sortOrder", 99);
        return req;
    }

    private MvcResult createZone(Map<String, Object> body) throws Exception {
        return exec(post("/api/staff/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
    }

    private JsonNode zones(String token, Long floorId) throws Exception {
        return data(exec(get("/api/zones").param("floorId", String.valueOf(floorId))
                .header("Authorization", bearer(token))));
    }

    private boolean hasZone(JsonNode list, String code) {
        for (JsonNode node : list) {
            if (code.equals(node.path("code").asText())) {
                return true;
            }
        }
        return false;
    }

    private Long zoneIdOf(JsonNode list, String code) {
        for (JsonNode node : list) {
            if (code.equals(node.path("code").asText())) {
                return node.path("zoneId").asLong();
            }
        }
        throw new AssertionError("分区列表中未找到 " + code);
    }

    @Test
    @DisplayName("【验收1】种子与迁移后不存在未归属分区的座位")
    void noOrphanSeats() throws Exception {
        assertEquals(0L, seatRepository.countByZoneIdIsNull(),
                "★ 每个座位都必须有 zone_id，否则分区规则无处生效");
        JsonNode all = data(exec(get("/api/zones").header("Authorization", bearer(u1))));
        long sumTotal = 0;
        for (JsonNode zone : all) {
            sumTotal += zone.path("totalSeats").asLong();
        }
        assertTrue(sumTotal > 0, "分区列表不应为空");
    }

    @Test
    @DisplayName("【验收2】教师专区对学生面不可见，对教师面可见")
    void teacherZoneVisibility() throws Exception {
        JsonNode studentView = zones(u1, floor2);
        assertFalse(hasZone(studentView, "2F-TEACHER"),
                "★ 学生不应看到教师专区（不是「看得见但约不了」）：" + studentView);
        assertTrue(hasZone(studentView, "2F-SILENT"), "学生应能看到普通分区");

        String teacher = login("teacher1", "123456");
        JsonNode teacherView = zones(teacher, floor2);
        assertTrue(hasZone(teacherView, "2F-TEACHER"), "教师应能看到教师专区");
    }

    @Test
    @DisplayName("【验收4】分区响应不含任何占用人身份信息")
    void zoneVoCarriesNoIdentity() throws Exception {
        String raw = exec(get("/api/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(u1))).getResponse().getContentAsString();
        for (String forbidden : List.of("userId", "username", "realName", "phone", "studentNo")) {
            assertFalse(raw.contains(forbidden), "响应不应出现字段 " + forbidden);
        }
    }

    @Test
    @DisplayName("【权限】/api/staff/zones 学生与教师 403，馆员放行")
    void staffNamespaceGuard() throws Exception {
        assertEquals(403, exec(get("/api/staff/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(u1))).getResponse().getStatus(), "学生不得进入馆员面");
        assertEquals(403, exec(get("/api/staff/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(login("teacher1", "123456"))))
                .getResponse().getStatus(), "教师不得进入馆员面");
        assertEquals(0, code(exec(get("/api/staff/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(login("librarian1", "123456"))))),
                "馆员应可读取分区");
    }

    @Test
    @DisplayName("【CRUD】新增→改名→关闭→删除，全程生效")
    void zoneCrud() throws Exception {
        Map<String, Object> body = zoneRequest(floor3, "QA-ZONE");
        MvcResult created = createZone(body);
        assertEquals(0, code(created), "新增分区应成功：" + body(created));
        long zoneId = data(created).path("zoneId").asLong();
        try {
            assertEquals("SILENT", data(created).path("zoneType").asText());
            assertEquals(120, data(created).path("maxDurationMin").asInt());

            body.put("name", "改名后的分区");
            body.put("maxDurationMin", 90);
            assertEquals(0, code(exec(put("/api/staff/zones/{id}", zoneId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(body))
                    .header("Authorization", bearer(admin)))), "编辑应成功");
            JsonNode adminView = data(exec(get("/api/staff/zones").param("floorId", String.valueOf(floor3))
                    .header("Authorization", bearer(admin))));
            assertEquals("改名后的分区", zoneNode(adminView, zoneId).path("name").asText());
            assertEquals(90, zoneNode(adminView, zoneId).path("maxDurationMin").asInt());

            assertEquals(0, code(exec(put("/api/staff/zones/{id}/status", zoneId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("status", "CLOSED")))
                    .header("Authorization", bearer(admin)))), "关闭分区应成功");
            assertEquals("CLOSED", zoneNode(data(exec(get("/api/staff/zones")
                    .param("floorId", String.valueOf(floor3))
                    .header("Authorization", bearer(admin)))), zoneId).path("status").asText(),
                    "★ 管理面应能看见已关闭分区（可见性过滤只作用于学生面）");
        } finally {
            exec(delete("/api/staff/zones/{id}", zoneId).header("Authorization", bearer(admin)));
        }
    }

    @Test
    @DisplayName("【校验】重复编码 4090，非法枚举 4001")
    void zoneValidation() throws Exception {
        assertEquals(CONFLICT, code(createZone(zoneRequest(floor1, "1F-SILENT"))),
                "同楼层编码重复必须被拒（uk_zone_floor_code）");
        Map<String, Object> bad = zoneRequest(floor3, "QA-BAD");
        bad.put("zoneType", "NOT_A_TYPE");
        assertEquals(PARAM_ERROR, code(createZone(bad)), "非法分区类型应 4001");
        Map<String, Object> badRole = zoneRequest(floor3, "QA-BAD2");
        badRole.put("visibleRoles", List.of("STUDENT", "KING"));
        assertEquals(PARAM_ERROR, code(createZone(badRole)), "非法角色名必须被拒（CSV 逐段校验）");
    }

    @Test
    @DisplayName("【删除守卫】分区内存在未来有效预约时 4109")
    void deleteBlockedByActiveReservation() throws Exception {
        long zoneId = zoneIdOf(zones(admin, floor1), "1F-SILENT");
        String date = d(3);
        // 静音区是 1F 的主力分区（1~4 行 = A01~A40），逐个试到约上为止
        List<Long> booked = new ArrayList<>();
        for (int no = 1; no <= 40 && booked.isEmpty(); no++) {
            MvcResult res = reserveRaw(u1, seatId(floor1, String.format("A%02d", no)), date, 20, 1);
            if (code(res) == 0) {
                booked.add(data(res).path("reservationId").asLong());
            }
        }
        assertEquals(1, booked.size(), "A01~A40 中应至少有一个可在 " + date + " 20:00 预约");
        try {
            MvcResult denied = exec(delete("/api/staff/zones/{id}", zoneId)
                    .header("Authorization", bearer(admin)));
            assertEquals(SPACE_IN_USE, code(denied),
                    "★ 分区内还有未来预约时必须拒绝删除：" + body(denied));
            assertTrue(hasZone(zones(admin, floor1), "1F-SILENT"), "被拒后分区应仍然存在");
        } finally {
            for (Long id : booked) {
                exec(post("/api/reservations/{id}/cancel", id).header("Authorization", bearer(u1)));
            }
        }
    }

    private JsonNode zoneNode(JsonNode list, long zoneId) {
        for (JsonNode node : list) {
            if (node.path("zoneId").asLong() == zoneId) {
                return node;
            }
        }
        throw new AssertionError("列表中未找到分区 " + zoneId);
    }
}

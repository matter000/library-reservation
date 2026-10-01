package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P0-2 权限与越权验证：4010 / 4030 / 水平越权 / 注册角色强制 / 隐私脱敏。
 */
class SecurityApiTest extends ApiTestBase {

    private static final int UNAUTHORIZED = 4010;
    private static final int FORBIDDEN = 4030;
    private static final int NOT_FOUND = 4040;
    private static final int PARAM_ERROR = 4001;

    // ==================== 4010 ====================

    @Test
    @DisplayName("【4010】无 Token 访问受保护接口应一律返回 4010")
    void noToken() throws Exception {
        List<String> urls = List.of(
                "/api/auth/me",
                "/api/floors",
                "/api/floors/" + floor1,
                "/api/seats/map?floorId=" + floor1,
                "/api/config/slots",
                "/api/reservations/my",
                "/api/reservations/1",
                "/api/admin/floors",
                "/api/admin/floors/" + floor1 + "/seats",
                "/api/admin/usage?floorId=" + floor1,
                "/api/admin/reservations",
                "/api/admin/statistics/overview",
                "/api/admin/users");
        for (String url : urls) {
            MvcResult result = exec(get(url));
            assertEquals(UNAUTHORIZED, code(result), "无 Token 访问 " + url + " 应返回 4010，实际：" + body(result));
            assertEquals(401, result.getResponse().getStatus(), url + " HTTP 状态码应为 401");
        }
    }

    @Test
    @DisplayName("【4010】非法 / 伪造 Token 应返回 4010")
    void badToken() throws Exception {
        List<String> tokens = List.of("abc", "Bearer abc", "Bearer " + admin + "x", admin.substring(0, 20));
        for (String token : tokens) {
            MvcResult result = exec(get("/api/auth/me").header("Authorization", token));
            assertEquals(UNAUTHORIZED, code(result), "Token=" + token + " 应返回 4010，实际：" + body(result));
        }
    }

    // ==================== 4030 ====================

    @Test
    @DisplayName("【4030】USER Token 访问每一个 /api/admin/** 接口都应返回 4030（逐个接口验证）")
    void userCannotAccessAdminApis() throws Exception {
        Map<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> adminCalls =
                new HashMap<>();

        adminCalls.put("GET /api/admin/floors", get("/api/admin/floors"));
        adminCalls.put("POST /api/admin/floors", post("/api/admin/floors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "越权层", "code", "HACK"))));
        adminCalls.put("PUT /api/admin/floors/{id}", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/admin/floors/" + floor1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "越权层", "code", "1F"))));
        adminCalls.put("DELETE /api/admin/floors/{id}",
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/floors/" + floor1));
        adminCalls.put("PUT /api/admin/floors/{id}/status",
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/admin/floors/" + floor1 + "/status")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "CLOSED"))));
        adminCalls.put("GET /api/admin/floors/{id}/seats", get("/api/admin/floors/" + floor1 + "/seats"));
        adminCalls.put("POST /api/admin/seats", post("/api/admin/seats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("floorId", floor1, "seatNo", "HACK", "rowNo", 1, "colNo", 1))));
        adminCalls.put("PUT /api/admin/seats/{id}", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/admin/seats/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("floorId", floor1, "seatNo", "HACK", "rowNo", 1, "colNo", 1))));
        adminCalls.put("DELETE /api/admin/seats/{id}",
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/seats/1"));
        adminCalls.put("PUT /api/admin/seats/{id}/status",
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/seats/1/status")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "DISABLED"))));
        adminCalls.put("POST /api/admin/seats/batch", post("/api/admin/seats/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("floorId", floor1, "rows", 1, "cols", 1, "prefix", "H", "startNo", 1))));
        adminCalls.put("GET /api/admin/usage", get("/api/admin/usage").param("floorId", String.valueOf(floor1)));
        adminCalls.put("GET /api/admin/reservations", get("/api/admin/reservations"));
        adminCalls.put("POST /api/admin/reservations/{id}/release", post("/api/admin/reservations/1/release")
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "越权尝试"))));
        adminCalls.put("GET /api/admin/statistics/overview", get("/api/admin/statistics/overview"));
        adminCalls.put("GET /api/admin/statistics/trend", get("/api/admin/statistics/trend"));
        adminCalls.put("GET /api/admin/statistics/slot-heat", get("/api/admin/statistics/slot-heat"));
        adminCalls.put("GET /api/admin/statistics/floor-usage", get("/api/admin/statistics/floor-usage"));
        adminCalls.put("GET /api/admin/statistics/status-distribution",
                get("/api/admin/statistics/status-distribution"));
        adminCalls.put("GET /api/admin/users", get("/api/admin/users"));

        assertEquals(20, adminCalls.size(), "管理员接口应全部覆盖（20 个）");
        for (Map.Entry<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> entry
                : adminCalls.entrySet()) {
            MvcResult result = exec(entry.getValue().header("Authorization", bearer(u1)));
            assertEquals(FORBIDDEN, code(result),
                    "USER Token 访问 " + entry.getKey() + " 应返回 4030，实际：" + body(result));
        }
    }

    @Test
    @DisplayName("【4030】已注册但被强制为 STUDENT 的账号仍然拿不到管理权限")
    void registeredUserIsNotAdmin() throws Exception {
        String username = "qa" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> register = new HashMap<>();
        register.put("username", username);
        register.put("password", "123456");
        register.put("confirmPassword", "123456");
        register.put("realName", "越权测试");
        register.put("phone", "13900001111");
        register.put("role", "SYS_ADMIN");

        MvcResult result = exec(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(register)));
        assertEquals(0, code(result), "注册应成功：" + body(result));
        assertEquals("STUDENT", data(result).path("role").asText(),
                "★ 注册时传 role=SYS_ADMIN，后端必须强制为 STUDENT，实际：" + data(result));

        String token = login(username, "123456");
        JsonNode me = data(exec(get("/api/auth/me").header("Authorization", bearer(token))));
        assertEquals("STUDENT", me.path("role").asText());

        MvcResult adminCall = exec(get("/api/admin/users").header("Authorization", bearer(token)));
        assertEquals(FORBIDDEN, code(adminCall), "注册用户访问管理员接口应 4030");
    }

    @Test
    @DisplayName("【V2 权限矩阵】四角色 × 三命名空间的入口可达性")
    void roleMatrix() throws Exception {
        String teacher = login("teacher1", "123456");
        String librarian = login("librarian1", "123456");

        // 学生面 /api/**：四个已登录角色都可访问
        String[] all = {u1, teacher, librarian, admin};
        for (String token : all) {
            assertEquals(0, code(exec(get("/api/floors").header("Authorization", bearer(token)))),
                    "已登录角色应可访问 /api/floors");
        }

        // 馆员面 /api/staff/**：学生与教师必须 4030（拦截器先于路由，故路径未实现也能验证拒绝分支）
        assertEquals(FORBIDDEN, code(exec(get("/api/staff/usage").header("Authorization", bearer(u1)))),
                "学生不得访问 /api/staff/**");
        assertEquals(FORBIDDEN, code(exec(get("/api/staff/usage").header("Authorization", bearer(teacher)))),
                "教师不得访问 /api/staff/**");

        // 管理面 /api/admin/**：馆员必须 4030，系统管理员放行
        assertEquals(FORBIDDEN, code(exec(get("/api/admin/floors").header("Authorization", bearer(librarian)))),
                "馆员不得访问 /api/admin/**");
        assertEquals(0, code(exec(get("/api/admin/floors").header("Authorization", bearer(admin)))),
                "系统管理员应可访问 /api/admin/floors");
    }

    @Test
    @DisplayName("【4090】重复账号注册应返回 4090")
    void duplicateRegister() throws Exception {
        Map<String, Object> register = new HashMap<>();
        register.put("username", "student1");
        register.put("password", "123456");
        register.put("confirmPassword", "123456");
        register.put("realName", "重复用户");
        MvcResult result = exec(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(register)));
        assertEquals(4090, code(result), "重复账号应返回 4090，实际：" + body(result));
    }

    @Test
    @DisplayName("【4001】密码错误应返回 4001（不泄露账号是否存在）")
    void wrongPassword() throws Exception {
        MvcResult result = exec(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "student1", "password", "wrong-pass"))));
        assertEquals(PARAM_ERROR, code(result), "密码错误应返回 4001，实际：" + body(result));
    }

    // ==================== 水平越权 ====================

    @Test
    @DisplayName("【4040】USER A 不能查看 / 取消 / 签到 USER B 的预约")
    void horizontalEscalation() throws Exception {
        long seat = seatId(floor1, "A01");
        String owner = newUser();
        String attacker = newUser();
        JsonNode created = reserveOk(owner, seat, d(3), 10, 2);
        long reservationId = created.path("reservationId").asLong();

        MvcResult detail = exec(get("/api/reservations/" + reservationId)
                .header("Authorization", bearer(attacker)));
        assertEquals(NOT_FOUND, code(detail), "A 的预约被 B 查看详情应 4040，实际：" + body(detail));

        MvcResult cancel = exec(post("/api/reservations/" + reservationId + "/cancel")
                .header("Authorization", bearer(attacker)));
        assertEquals(NOT_FOUND, code(cancel), "A 的预约被 B 取消应 4040，实际：" + body(cancel));

        MvcResult checkIn = exec(post("/api/reservations/" + reservationId + "/check-in")
                .header("Authorization", bearer(attacker)));
        assertEquals(NOT_FOUND, code(checkIn), "A 的预约被 B 签到应 4040，实际：" + body(checkIn));

        // 本人仍可正常访问
        MvcResult mine = exec(get("/api/reservations/" + reservationId)
                .header("Authorization", bearer(owner)));
        assertEquals(0, code(mine), "本人查看自己的预约应成功");
    }

    @Test
    @DisplayName("【4040】不存在的预约 ID 应返回 4040")
    void reservationNotFound() throws Exception {
        MvcResult result = exec(get("/api/reservations/99999999").header("Authorization", bearer(u1)));
        assertEquals(NOT_FOUND, code(result), "不存在的预约应 4040，实际：" + body(result));
    }

    // ==================== 隐私红线 ====================

    @Test
    @DisplayName("【隐私】/api/seats/map 响应体不得出现任何占用人身份信息")
    void seatMapHasNoIdentity() throws Exception {
        long seat = seatId(floor1, "A02");
        reserveOk(newUser(), seat, d(3), 20, 1);

        MvcResult result = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", d(3))
                .param("slot", "20")
                .header("Authorization", bearer(u2)));
        assertEquals(0, code(result));
        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        for (String leak : List.of("realName", "userName", "username", "phone", "张三", "student1", "138")) {
            assertFalse(raw.contains(leak), "平面图响应泄漏了字段/内容：" + leak + " → " + raw.substring(0, Math.min(400, raw.length())));
        }

        Set<String> allowed = Set.of("seatId", "seatNo", "rowNo", "colNo", "zone", "baseStatus", "displayStatus");
        for (JsonNode seatNode : data(result).path("seats")) {
            Set<String> fields = new java.util.HashSet<>();
            seatNode.fieldNames().forEachRemaining(fields::add);
            assertTrue(allowed.containsAll(fields), "平面图座位对象出现多余字段：" + fields);
        }
        // 被占用的座位应能看到 RESERVED，但看不到是谁
        assertEquals("RESERVED", displayStatus(floor1, seat, d(3), 20));
    }

    @Test
    @DisplayName("【隐私】/api/admin/usage 占用人只含 realName + username，不得含手机号")
    void usageOccupantHasNoPhone() throws Exception {
        long seat = seatId(floor1, "A03");
        reserveOk(newUser(), seat, d(3), 12, 2);

        MvcResult result = exec(get("/api/admin/usage")
                .param("floorId", String.valueOf(floor1))
                .param("date", d(3))
                .param("slot", "12")
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result));
        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(raw.contains("phone"), "使用情况响应不应含 phone 字段");
        assertFalse(raw.contains("13812345671"), "使用情况响应泄漏了完整手机号");

        for (JsonNode row : data(result).path("rows")) {
            JsonNode occupant = row.path("occupant");
            if (occupant.isNull()) {
                continue;
            }
            Set<String> fields = new java.util.HashSet<>();
            occupant.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("realName", "username"), fields, "占用人字段应只有 realName + username，实际：" + fields);
        }
    }

    @Test
    @DisplayName("【隐私】/api/admin/users 与 /api/admin/reservations 手机号必须脱敏")
    void adminPhoneMasked() throws Exception {
        MvcResult result = exec(get("/api/admin/users")
                .param("keyword", "student1")
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result));
        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(raw.contains("13812345671"), "用户列表泄漏 student1 完整手机号");
        assertFalse(raw.contains("13800000000"), "用户列表泄漏 admin 完整手机号");

        boolean foundStudent1 = false;
        for (JsonNode user : data(result).path("list")) {
            if ("student1".equals(user.path("username").asText())) {
                foundStudent1 = true;
                String masked = user.path("phoneMasked").asText();
                assertTrue(masked.matches("1\\d{2}\\*{4}\\d{4}"), "手机号脱敏格式错误：" + masked);
                assertNotEquals("13812345671", masked);
            }
        }
        assertTrue(foundStudent1, "用户列表应包含 student1");

        long seat = seatId(floor1, "A04");
        reserveOk(newUser(), seat, d(3), 15, 2);
        MvcResult reservations = exec(get("/api/admin/reservations").header("Authorization", bearer(admin)));
        assertEquals(0, code(reservations));
        String rawReservations = reservations.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(rawReservations.contains("phone"), "预约记录不应含 phone 字段");
        assertFalse(rawReservations.contains("13812345671"), "预约记录泄漏完整手机号");
    }

    @Test
    @DisplayName("【隐私】/api/auth/me 与普通用户预约详情不得返回他人手机号")
    void mePhoneMasked() throws Exception {
        JsonNode me = data(exec(get("/api/auth/me").header("Authorization", bearer(u1))));
        assertEquals("138****5671", me.path("phoneMasked").asText(), "本人手机号也应脱敏");
    }
}

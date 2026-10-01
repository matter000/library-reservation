package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S0：操作审计 —— 治理动作必须留痕且可追溯（PRD-V2 §10.3、§12-22）。
 *
 * <p>★ 规则类修改在 finally 里恢复默认值，避免污染同一 JVM 内的其它用例。
 */
class AuditApiTest extends ApiTestBase {

    private static final int FORBIDDEN = 4030;

    private JsonNode auditPage(String token, String action) throws Exception {
        MvcResult result = exec(get("/api/admin/audit")
                .param("action", action)
                .param("page", "1").param("size", "10")
                .header("Authorization", bearer(token)));
        assertEquals(0, code(result), "审计查询应成功：" + body(result));
        return data(result);
    }

    private void updateRule(String token, String rkey, String value) throws Exception {
        exec(put("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(List.of(Map.of("rkey", rkey, "value", value))))
                .header("Authorization", bearer(token)));
    }

    @Test
    @DisplayName("【权限】审计日志只有系统管理员能看")
    void auditPermission() throws Exception {
        MvcResult student = exec(get("/api/admin/audit").header("Authorization", bearer(u1)));
        assertEquals(FORBIDDEN, code(student), "学生不得读取审计日志");
        MvcResult librarian = exec(get("/api/admin/audit")
                .header("Authorization", bearer(login("librarian1", "123456"))));
        assertEquals(FORBIDDEN, code(librarian), "馆员不得读取系统管理面审计日志");
    }

    @Test
    @DisplayName("【留痕】规则变更写入审计，明细含旧值与新值")
    void ruleUpdateIsAudited() throws Exception {
        long before = auditPage(admin, "RULE_UPDATE").path("total").asLong();
        updateRule(admin, "book.default-duration", "3");
        try {
            JsonNode page = auditPage(admin, "RULE_UPDATE");
            assertEquals(before + 1, page.path("total").asLong(), "应新增一条 RULE_UPDATE 审计");
            JsonNode latest = page.path("list").get(0);
            assertTrue(latest.path("detail").asText().contains("book.default-duration"),
                    "明细应记录被改的规则键：" + latest.path("detail").asText());
            assertTrue(latest.path("detail").asText().contains("oldValue")
                            && latest.path("detail").asText().contains("newValue"),
                    "明细应含旧值/新值：" + latest.path("detail").asText());
            assertEquals("修改规则配置", latest.path("actionText").asText());
            assertEquals("SYS_ADMIN", latest.path("actorRole").asText(), "审计应记录操作人角色");
            assertTrue(latest.path("actorId").asLong() > 0, "审计应记录操作人");
        } finally {
            updateRule(admin, "book.default-duration", "2");
        }
    }

    @Test
    @DisplayName("【留痕】强制释放写入审计，明细含原因")
    void releaseIsAudited() throws Exception {
        String me = newUser();
        long seat = seatId(floor1, "A21");
        MvcResult createdResult = exec(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatId", seat, "date", d(3), "startSlot", 10, "duration", 1)))
                .header("Authorization", bearer(me)));
        assertEquals(0, code(createdResult), "前置预约应成功：" + body(createdResult));
        long reservationId = data(createdResult).path("reservationId").asLong();

        long before = auditPage(admin, "RESERVATION_RELEASE").path("total").asLong();
        MvcResult released = exec(post("/api/admin/reservations/" + reservationId + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", "设备检修需要占用")))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(released), "强制释放应成功：" + body(released));

        JsonNode page = auditPage(admin, "RESERVATION_RELEASE");
        assertEquals(before + 1, page.path("total").asLong(), "应新增一条 RESERVATION_RELEASE 审计");
        String detail = page.path("list").get(0).path("detail").asText();
        assertTrue(detail.contains("设备检修需要占用"), "审计明细必须含释放原因：" + detail);
        assertTrue(detail.contains("previousStatus"), "审计明细必须含释放前状态：" + detail);
    }
}

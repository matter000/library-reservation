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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S0：规则配置中心 —— 读写权限、值校验、以及「改了立即生效」。
 *
 * <p>★ 配置是全局态：任何修改都在 finally 里恢复默认值，避免污染同一 JVM 内的其它用例。
 */
class RuleApiTest extends ApiTestBase {

    private static final int FORBIDDEN = 4030;
    private static final int PARAM_ERROR = 4001;

    private MvcResult updateRule(String token, String rkey, String value) throws Exception {
        return exec(put("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(List.of(Map.of("rkey", rkey, "value", value))))
                .header("Authorization", bearer(token)));
    }

    private String ruleValue(String rkey) throws Exception {
        JsonNode list = data(exec(get("/api/admin/rules?rgroup=BOOK")
                .header("Authorization", bearer(admin))));
        for (JsonNode node : list) {
            if (rkey.equals(node.path("rkey").asText())) {
                return node.path("rvalue").asText();
            }
        }
        throw new AssertionError("BOOK 组规则列表中未找到 " + rkey);
    }

    @Test
    @DisplayName("【权限】仅系统管理员可读写规则；学生与教师一律 4030")
    void rulePermission() throws Exception {
        assertEquals(FORBIDDEN, code(exec(get("/api/admin/rules").header("Authorization", bearer(u1)))),
                "学生不得读取规则");
        assertEquals(FORBIDDEN, code(updateRule(u1, "book.advance-days", "3")), "学生不得修改规则");
        assertEquals(FORBIDDEN, code(updateRule(u2, "book.advance-days", "3")), "教师不得修改规则");
        assertEquals(0, code(exec(get("/api/admin/rules").header("Authorization", bearer(admin)))),
                "系统管理员应可读取规则");
    }

    @Test
    @DisplayName("【列表】已接线的 5 个 BOOK 键必须都在配置表里")
    void wiredKeysMustExist() throws Exception {
        // ruleValue() 找不到键即抛 AssertionError，因此调用本身就是「键必须存在」的断言
        for (String rkey : List.of("book.check-in-before-min", "book.check-in-after-min",
                "book.advance-days", "book.default-duration", "book.allowed-durations")) {
            ruleValue(rkey);
        }
        assertEquals("7", ruleValue("book.advance-days"));
    }

    @Test
    @DisplayName("【校验】非法值与未知键都返回 4001 且不落库")
    void ruleValidation() throws Exception {
        assertEquals(PARAM_ERROR, code(updateRule(admin, "book.advance-days", "abc")),
                "INT 规则传非整数应 4001");
        assertEquals(PARAM_ERROR, code(updateRule(admin, "not.exists", "1")),
                "未知键必须 4001（新增规则只能走版本化迁移脚本）");
        assertEquals("7", ruleValue("book.advance-days"), "非法写入不得污染配置");

        assertEquals(0, code(updateRule(admin, "book.advance-days", "3")), "合法值应成功");
        assertEquals("3", ruleValue("book.advance-days"));
        updateRule(admin, "book.advance-days", "7");
    }

    @Test
    @DisplayName("【生效】收窄 book.advance-days 后，预约窗口立即变短（不发版）")
    void ruleTakesEffect() throws Exception {
        String me = newUser();
        long seatA06 = seatId(floor1, "A06");
        long seatA07 = seatId(floor1, "A07");
        // 种子数据只覆盖 今天-6 ~ 今天+2，故 d(5) 一定无人占用，可稳定验证「窗口」这一步校验
        String beyond = d(5);

        assertEquals(0, code(reserveRaw(me, seatA06, beyond, 14, 1)), "默认 7 天窗口内应可预约");
        try {
            assertEquals(0, code(updateRule(admin, "book.advance-days", "1")), "修改规则应成功");
            MvcResult rejected = reserveRaw(me, seatA06, beyond, 15, 1);
            assertEquals(PARAM_ERROR, code(rejected),
                    "★ 窗口收窄为 1 天后，第 5 天必须被拒；若通过说明消费路径没接上配置：" + body(rejected));
            assertTrue(msg(rejected).contains("1 天"), "提示文案应反映当前配置，实际：" + msg(rejected));
        } finally {
            updateRule(admin, "book.advance-days", "7");
        }
        assertEquals(0, code(reserveRaw(newUser(), seatA07, beyond, 14, 1)), "恢复默认后应重新可预约");
    }

    @Test
    @DisplayName("【用户端】/api/config/booking 任何登录角色可读，且值随规则变化")
    void bookingConfigExposedToUsers() throws Exception {
        MvcResult anonymous = exec(get("/api/config/booking"));
        assertEquals(4010, code(anonymous), "未登录不得读取配置");

        JsonNode asStudent = data(exec(get("/api/config/booking").header("Authorization", bearer(u1))));
        assertEquals(7, asStudent.path("advanceDays").asInt(), "默认窗口应为 7 天");
        assertEquals(4, asStudent.path("allowedDurations").size(), "默认可选时长 4 项");

        try {
            assertEquals(0, code(updateRule(admin, "book.advance-days", "2")), "修改规则应成功");
            JsonNode again = data(exec(get("/api/config/booking").header("Authorization", bearer(u1))));
            assertEquals(2, again.path("advanceDays").asInt(),
                    "★ 用户端表单必须看到收窄后的窗口，否则日期选择器会与后端校验打架");
        } finally {
            updateRule(admin, "book.advance-days", "7");
        }
    }
}

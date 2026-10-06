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
    @DisplayName("【种子自洽】分区日累计不得比全馆口径更宽，考试周档不得比平常窄")
    void seedCapsMustNotContradict() throws Exception {
        int globalCap = Integer.parseInt(ruleValue("book.daily-cap-min"));
        int examCap = Integer.parseInt(ruleValue("book.daily-cap-exam-min"));
        assertTrue(examCap >= globalCap,
                "考试周是「放宽」：daily-cap-exam-min(" + examCap + ") 不得小于全馆口径(" + globalCap + ")");

        // ★ 分区级上限只能「本区收紧」。种得比全馆宽并不是更宽松 —— 全馆那道先拦，
        //   分区那道用户永远看不见，但配置面上写着"考研专座 8 小时"，人会按它理解系统。
        List<Map<String, Object>> wider = jdbc.queryForList(
                "select code, daily_cap_min from t_zone"
                        + " where daily_cap_min is not null and daily_cap_min > ?",
                globalCap);
        assertTrue(wider.isEmpty(),
                "以下分区段的日累计上限比全馆口径更宽，属于永不生效的死配置：" + wider);
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
        // 种子数据只覆盖 今天-6 ~ 今天+2，故窗口内最远那天一定无人占用，可稳定验证「窗口」这一步校验。
        // 必须取工作日：周末档案是 09:00-21:00，晚间时段会被开放窗口拒绝，用例就变成星期敏感。
        String beyond = dw(5);

        assertEquals(0, code(reserveRaw(me, seatA06, beyond, slot(14), 1)), "默认窗口内应可预约");

        Long zoneId = jdbc.queryForObject("select zone_id from t_seat where id = ?", Long.class, seatA06);
        assertTrue(zoneId != null, "A06 应已归属分区（V2 起分区是规则载体）");
        try {
            assertEquals(0, code(updateRule(admin, "book.advance-days", "1")), "修改规则应成功");

            // ① 分区配了本区口径时，改全局值不牵连它 —— 口径是「分区取代全局」，不是取小。
            //    若哪天有人把它改回 min(全局, 分区)，这条就会红。
            MvcResult zoneWins = reserveRaw(me, seatA06, beyond, slot(16), 1);
            assertEquals(0, code(zoneWins),
                    "静音区写着提前 3 天，全局收窄到 1 天也不该牵连它：" + body(zoneWins));

            // ② 分区没配时才回落到全局规则值，此时改全局立即生效
            jdbc.update("update t_zone set advance_days = null where id = ?", zoneId);
            MvcResult rejected = reserveRaw(me, seatA06, beyond, slot(18), 1);
            assertEquals(PARAM_ERROR, code(rejected),
                    "★ 分区未配 → 走全局值；窗口收窄为 1 天后 today+2 必须被拒；"
                            + "若通过说明消费路径没接上配置：" + body(rejected));
            assertTrue(msg(rejected).contains("1 天"), "提示文案应反映当前配置，实际：" + msg(rejected));
        } finally {
            jdbc.update("update t_zone set advance_days = 3 where id = ?", zoneId);
            updateRule(admin, "book.advance-days", "7");
        }
        assertEquals(0, code(reserveRaw(newUser(), seatA07, beyond, slot(14), 1)), "恢复默认后应重新可预约");
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

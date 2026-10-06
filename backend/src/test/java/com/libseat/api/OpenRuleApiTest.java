package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S1 / T-V2-07：30 分钟时段 + 开放档案 / 开放日历。
 *
 * <p>核心不是「能不能配」，而是配了之后<b>是否真的生效</b>：
 * 闭馆必须同时拦住预约与平面图，窗口变化必须改变时段选项的格数。
 */
class OpenRuleApiTest extends ApiTestBase {

    private static final int PARAM_ERROR = 4001;

    private JsonNode dayRule(String token, String date) throws Exception {
        return data(exec(get("/api/admin/open/day-rule")
                .param("date", date)
                .header("Authorization", bearer(token))));
    }

    private Map<String, Object> calendarRequest(String date, boolean closed) {
        Map<String, Object> req = new HashMap<>();
        req.put("date", date);
        req.put("closed", closed);
        req.put("remark", closed ? "设备检修，全天闭馆" : null);
        return req;
    }

    @Test
    @DisplayName("【档案】四档齐全，工作日窗口按 PRD §12-1（07:30-22:30 / 30 格）")
    void profilesSeeded() throws Exception {
        JsonNode profiles = data(exec(get("/api/admin/open/profiles")
                .header("Authorization", bearer(admin))));
        assertEquals(4, profiles.size(), "应种子 WORKDAY / WEEKEND / HOLIDAY / EXAM 四档");
        boolean exam = false;
        for (JsonNode node : profiles) {
            if ("EXAM".equals(node.path("dayType").asText())) {
                exam = true;
            }
        }
        assertTrue(exam, "★ 考试周必须是系统里的一个日型：PRD §12-3「考试周 8 小时」全靠它才有承载点，"
                + "否则那条规则只能永远无处生效");
        JsonNode workday = null;
        for (JsonNode node : profiles) {
            if ("WORKDAY".equals(node.path("dayType").asText())) {
                workday = node;
            }
        }
        assertTrue(workday != null, "必须存在工作日档案");
        assertEquals("07:30", workday.path("startTime").asText());
        assertEquals("22:30", workday.path("endTime").asText());
        assertEquals(30, workday.path("slotCount").asInt(),
                "★ PRD §12-1：工作日 07:30-22:30，30 分钟一格共 30 格");
    }

    @Test
    @DisplayName("【日型解析】工作日与周末落在不同档案上，边界随窗口移动")
    void dayTypeResolution() throws Exception {
        JsonNode weekday = dayRule(admin, dw(1));
        JsonNode weekend = dayRule(admin, nextWeekend());
        assertEquals("WORKDAY", weekday.path("dayType").asText(), "dw(1) 必须解析为工作日");
        assertEquals("WEEKEND", weekend.path("dayType").asText(), "周末日必须解析为 WEEKEND");
        assertEquals(15, weekday.path("openStart").asInt());
        assertEquals(45, weekday.path("openEnd").asInt());
        assertEquals(16, weekend.path("openStart").asInt(), "PRD §12-1：周末 08:00 起");
        assertEquals(44, weekend.path("openEnd").asInt(), "PRD §12-1：周末 22:00 止");
        assertNotEquals(weekday.path("slotCount").asInt(), weekend.path("slotCount").asInt());
    }

    @Test
    @DisplayName("【验收3】标记某日闭馆 → 该日预约 4001、平面图全 UNAVAILABLE、时段选项空")
    void closedDayBlocksEverything() throws Exception {
        String date = dw(4);
        long seat = seatId(floor1, "A31");
        assertEquals(0, code(reserveRaw(newUser(), seat, date, slot(10), 1)),
                "闭馆前该日应可正常预约（前置校验）");

        MvcResult saved = exec(post("/api/admin/open/calendar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(calendarRequest(date, true)))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(saved), "写日历覆盖应成功：" + body(saved));
        long calendarId = data(saved).path("id").asLong();
        try {
            JsonNode rule = dayRule(admin, date);
            assertEquals(true, rule.path("closed").asBoolean(), "生效规则必须立即反映闭馆");
            assertEquals(0, rule.path("slotCount").asInt(), "闭馆日可约格数应为 0");
            assertTrue(rule.path("source").asText().contains("日历覆盖"),
                    "来源必须说明是日历覆盖，否则管理员无法判断为什么关：" + rule.path("source").asText());

            MvcResult denied = reserveRaw(newUser(), seat, date, slot(12), 1);
            assertEquals(PARAM_ERROR, code(denied), "★ 闭馆日预约必须被拒：" + body(denied));
            assertTrue(msg(denied).contains("闭馆"), "提示应说明闭馆，实际：" + msg(denied));

            JsonNode map = data(exec(get("/api/seats/map")
                    .param("floorId", String.valueOf(floor1))
                    .param("date", date).param("slot", String.valueOf(slot(12)))
                    .header("Authorization", bearer(u1))));
            JsonNode stats = map.path("stats");
            assertEquals(0, stats.path("free").asInt(),
                    "★ 闭馆日平面图不得还剩空闲座：" + stats);
            assertTrue(stats.path("unavailable").asInt() > 0, "闭馆日应整体转为不可用");
            for (JsonNode seatNode : map.path("seats")) {
                assertEquals("UNAVAILABLE", seatNode.path("displayStatus").asText(),
                        "闭馆日每个座位都应是 UNAVAILABLE，实际：" + seatNode.path("seatNo"));
            }

            JsonNode options = data(exec(get("/api/config/slots")
                    .param("date", date).header("Authorization", bearer(u1))));
            assertEquals(0, options.size(), "闭馆日时段选项应为空");
        } finally {
            exec(delete("/api/admin/open/calendar/{id}", calendarId)
                    .header("Authorization", bearer(admin)));
        }
        // 删除覆盖后必须回到日型档案（缓存不失效就会残留闭馆状态）
        assertEquals(false, dayRule(admin, date).path("closed").asBoolean(),
                "★ 删除覆盖后应恢复开放，否则说明写入后没有清缓存");
    }

    @Test
    @DisplayName("【验收1】预约的时长仍是整小时、区间标签由存储格号单一推导，与 V1 语义一致")
    void migratedReservationsKeepMeaning() throws Exception {
        JsonNode list = data(exec(get("/api/admin/reservations")
                .param("page", "1").param("size", "200")
                .header("Authorization", bearer(admin)))).path("list");
        int sampled = 0;
        for (JsonNode reservation : list) {
            int start = reservation.path("startSlot").asInt();
            int end = reservation.path("endSlot").asInt();
            if (end <= start) {
                continue;
            }
            int span = end - start;
            // V2 允许半点起始（如 41 = 20:30），但可选时长仍是 1~4 整小时，故跨度必须是偶数格
            assertEquals(0, span % com.libseat.common.BizConstants.SLOTS_PER_HOUR,
                    "跨度必须是整小时格数，否则说明写入侧没做单位换算：" + reservation.path("reservationId"));
            int hours = com.libseat.common.BizConstants.hoursOfSlots(span);
            assertTrue(hours >= 1 && hours <= 4, "时长应仍是 1~4 小时，实际 " + hours);
            // 标签必须由同一口径推导：这一步能抓住「把格数当小时」的显示翻倍 bug
            assertEquals(com.libseat.common.BizConstants.rangeLabel(start, hours),
                    reservation.path("timeRange").asText(),
                    "区间标签必须由 startSlot/endSlot 与统一换算推导：" + reservation.path("reservationId"));
            if (++sampled >= 5) {
                break;
            }
        }
        assertEquals(5, sampled, "应至少抽样核对 5 条预约");
    }

    @Test
    @DisplayName("【窗口下界】把开门改到 10:00 后，09:00 起的预约必须被拒 —— 下界不能由网格代职")
    void lowerBoundFollowsProfile() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("dayType", "WORKDAY");
        req.put("name", "工作日 10:00-22:00");
        req.put("openStart", 20);
        req.put("openEnd", 44);
        assertEquals(0, code(exec(post("/api/admin/open/profiles")
                .contentType(MediaType.APPLICATION_JSON).content(json(req))
                .header("Authorization", bearer(admin)))), "调整工作日开窗应成功");
        try {
            String me = newUser();
            int start = slot(9);
            long seat = freeSeatOn(floor1, dw(1), start, 2);
            MvcResult r = exec(post("/api/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("seatId", seat, "date", dw(1),
                            "startSlot", start, "duration", 1)))
                    .header("Authorization", bearer(me)));
            String raw = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(PARAM_ERROR, code(r),
                    "★ 开窗已被管理员改到 10:00，09:00 起仍可约就说明没人校验下界（此前由 SLOT_INDEX_MIN=16 代职）：" + raw);
            assertTrue(raw.contains("10:00"), "报错要说当天实际开窗时刻，而不是网格常量：" + raw);
        } finally {
            req.put("name", "工作日 07:30-22:30");
            req.put("openStart", 15);
            req.put("openEnd", 45);
            exec(post("/api/admin/open/profiles").contentType(MediaType.APPLICATION_JSON)
                    .content(json(req)).header("Authorization", bearer(admin)));
        }
    }

    @Test
    @DisplayName("【校验】结束时段不大于起始时段 → 4001")
    void profileValidation() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("dayType", "WORKDAY");
        req.put("name", "非法窗口");
        req.put("openStart", 30);
        req.put("openEnd", 30);
        MvcResult bad = exec(post("/api/admin/open/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req))
                .header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(bad), "起止相等（0 格）必须被拒");

        req.put("dayType", "NOT_A_DAY_TYPE");
        req.put("openEnd", 40);
        assertEquals(PARAM_ERROR, code(exec(post("/api/admin/open/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req))
                .header("Authorization", bearer(admin)))), "非法日型必须被拒");
    }

    @Test
    @DisplayName("【权限】开放时间仅系统管理员可读写，馆员与教师 403")
    void adminOnly() throws Exception {
        for (String token : new String[]{login("librarian1", "123456"), login("teacher1", "123456"), u1}) {
            assertEquals(403, exec(get("/api/admin/open/profiles")
                    .header("Authorization", bearer(token))).getResponse().getStatus(),
                    "非系统管理员不得读开放时间");
        }
        assertEquals(0, code(exec(get("/api/admin/open/profiles")
                .header("Authorization", bearer(admin)))));
    }

    @Test
    @DisplayName("【窗口】周末 21:00 之后不可约，但同格在工作日可约（证明按日型判定而非全局常量）")
    void weekendWindowIsEnforced() throws Exception {
        String weekend = nextWeekend();
        String weekday = dw(1);
        // 周末止于 22:00（格 44），从 21:30（格 43）起约 1 小时会越界；
        // 工作日止于 22:30（格 45），同一格贴着边界，必须可约 —— 两档之差就是档案本身
        MvcResult weekendDenied = reserveRaw(newUser(), seatId(floor1, "A32"), weekend, 43, 1);
        assertEquals(PARAM_ERROR, code(weekendDenied),
                "★ 周末 21:30 起约 1 小时应越过 22:00 边界被拒：" + body(weekendDenied));
        MvcResult weekdayOk = reserveRaw(newUser(), seatId(floor1, "A33"), weekday, 43, 1);
        assertEquals(0, code(weekdayOk),
                "同一格在工作日（22:30 止）应可约：" + body(weekdayOk));
    }

    /** 直接取响应正文：本类没有 body() 助手，用同一套 StandardCharsets 读法。 */
    private String rawOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("【考试周窗口】覆盖成考试周后 07:00 可约、06:30 与 23:00 被拒（PRD §12-1 的那两小时）")
    void examWeekWindowIsEnforceable() throws Exception {
        String day = dw(1);
        Map<String, Object> req = new HashMap<>();
        req.put("date", day);
        req.put("dayType", "EXAM");
        assertEquals(0, code(exec(post("/api/admin/open/calendar")
                .contentType(MediaType.APPLICATION_JSON).content(json(req))
                .header("Authorization", bearer(admin)))), "覆盖成考试周应成功");
        try {
            JsonNode rule = data(exec(get("/api/admin/open/day-rule")
                    .param("date", day).header("Authorization", bearer(admin))));
            assertEquals("EXAM", rule.path("dayType").asText(), "日历覆盖必须真的改变当天日型");
            assertEquals(14, rule.path("openStart").asInt(), "考试周应 07:00 开门");
            assertEquals(46, rule.path("openEnd").asInt(), "考试周应 23:00 闭馆");

            // ★ 这道断言才是 §12-1 那两小时的验收：档案能写不等于订得到，
            //   此前 07:00 会被网格下界（08:00）直接判非法，规则写在库里却走不通。
            long seat = freeSeatOn(floor1, day, 14, 2);
            String raw = rawOf(exec(post("/api/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("seatId", seat, "date", day,
                            "startSlot", 14, "duration", 1)))
                    .header("Authorization", bearer(u1))));
            assertTrue(raw.contains("\"code\":0"), "考试周 07:00 起约 1 小时必须可约：" + raw);

            // 网格外的两头仍然要拒：06:30（格 13）与 23:00（格 46，开区间边界）
            assertEquals(PARAM_ERROR, code(reserveRaw(u1, seat, day, 13, 1)),
                    "06:30 不在网格内，必须 4001");
            assertEquals(PARAM_ERROR, code(reserveRaw(u1, seat, day, 46, 1)),
                    "23:00 是闭馆时刻，不能作为起始格");
        } finally {
            jdbc.update("delete from t_open_calendar where open_date = ?",
                    java.time.LocalDate.parse(day));
        }
    }

        @Test
    @DisplayName("【当前时段】nowSlot 必须按 30 分钟格返回，而非小时")
    void slotOptionsIndexIsHalfHourBased() throws Exception {
        JsonNode options = data(exec(get("/api/config/slots")
                .param("date", dw(1)).header("Authorization", bearer(u1))));
        assertEquals(30, options.size(), "工作日档案 07:30-22:30 ⇒ 30 格");
        // 格序号必须从 15（07:30）开始且逐格 +1，标签必须体现半点格
        assertEquals(15, options.get(0).path("slotIndex").asInt());
        assertEquals("07:30-08:00", options.get(0).path("label").asText());
        assertEquals("08:00-08:30", options.get(1).path("label").asText());
    }

    @Test
    @DisplayName("【审计】开放时间变更必须留痕")
    void openRuleChangesAreAudited() throws Exception {
        String date = LocalDate.now().plusDays(20).toString();
        MvcResult saved = exec(post("/api/admin/open/calendar")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(calendarRequest(date, true)))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(saved));
        long calendarId = data(saved).path("id").asLong();
        try {
            JsonNode audit = data(exec(get("/api/admin/audit")
                    .param("action", "OPEN_RULE_SAVE").param("size", "5")
                    .header("Authorization", bearer(admin))));
            assertTrue(audit.path("total").asInt() >= 1,
                    "★ 改了开放时间必须留痕，否则事后无法追责：" + audit);
            assertEquals("维护开放时间",
                    audit.path("list").get(0).path("actionText").asText());
        } finally {
            exec(delete("/api/admin/open/calendar/{id}", calendarId)
                    .header("Authorization", bearer(admin)));
        }
    }
}

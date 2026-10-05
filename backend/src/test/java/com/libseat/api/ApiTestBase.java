package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 接口集成测试基类：独立 H2（in-memory）+ 真实拦截器 + MockMvc。
 *
 * <p>种子数据由 {@code DataInitializer} 写入：3 楼层 / 124 座位 / 若干历史预约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ApiTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ReservationRepository reservationRepository;

    @Autowired
    protected ReservationSlotRepository reservationSlotRepository;

    /** 管理员 Token。 */
    protected String admin;

    /** 学生 Token：student1 / student2 / student3。 */
    protected String u1;
    protected String u2;
    protected String u3;

    /** 三个楼层的 ID（按 sortOrder 升序）。 */
    protected Long floor1;
    protected Long floor2;
    protected Long floor3;

    /** 种子数据里处在 MAINTENANCE 的座位编号。 */
    protected static final String MAINT_1F = "A15";
    protected static final String MAINT_2F = "B08";
    protected static final String MAINT_3F = "C03";

    @BeforeEach
    void baseSetUp() throws Exception {
        admin = login("admin", "admin123");
        u1 = login("student1", "123456");
        u2 = login("student2", "123456");
        u3 = login("student3", "123456");

        JsonNode floors = data(exec(get("/api/floors").header("Authorization", bearer(u1))));
        assertTrue(floors.size() >= 3, "种子数据应至少有 3 个楼层，实际 " + floors.size());
        floor1 = floors.get(0).path("floorId").asLong();
        floor2 = floors.get(1).path("floorId").asLong();
        floor3 = floors.get(2).path("floorId").asLong();
    }

    // ==================== 基础工具 ====================

    /**
     * 整点 → 30 分钟格序号。
     *
     * <p>V2 的格长是 30 分钟，用例仍以「小时」表达意图，避免到处写 ×2 的魔法数。
     *
     * @param hour 整点小时
     * @return 时段序号
     */
    protected static int slot(int hour) {
        return hour * com.libseat.common.BizConstants.SLOTS_PER_HOUR;
    }

    /**
     * 夹具能安全使用的最远日期偏移。
     *
     * <p>★ 这里跟的不再是全局 {@code book.advance-days}，而是**分区口径**：
     * V2 起「分区值取代全局默认」（PRD §12-5 分区是规则载体），种子里多数分区写的是提前 3 天（含当天），
     * 也就是 {@code today..today+2}。以前按全局 7 天取日期，等于所有用例都默认活在最宽的那档分区里，
     * 一旦某个分区的本区口径更紧就整批失真。
     */
    protected static final int FIXTURE_MAX_OFFSET = 2;

    /**
     * 第 n 个<b>工作日</b>的日期偏移（周六周日跳过）。
     *
     * <p>V2 起开放窗口按日型不同（工作日 08:00-22:00、周末 09:00-21:00），
     * 用例若固定写 {@code d(3)} 会随「今天是星期几」落到周末，晚间时段就会被正确拒绝，
     * 表现为与代码无关的星期依赖性 flaky。需要完整窗口时用本方法取日期。
     *
     * @param weekdays 工作日序号（1 表示下一个工作日）
     * @return 相对今天的日历日偏移，可直接传给 {@link #d(int)}
     */
    protected static int weekdayOffset(int weekdays) {
        int step = 0;
        for (int counted = 0; counted < weekdays; ) {
            step++;
            java.time.DayOfWeek dow = java.time.LocalDate.now().plusDays(step).getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                counted++;
            }
        }
        // 仍需落在分区可约窗口内，否则用例会因为窗口校验而失真
        return Math.min(step, FIXTURE_MAX_OFFSET);
    }

    /**
     * 第 n 个工作日对应的日期字符串。
     *
     * @param weekdays 工作日序号
     * @return yyyy-MM-dd
     */
    protected static String dw(int weekdays) {
        return d(weekdayOffset(weekdays));
    }

    /**
     * 最近一个周六或周日的日期（用于对比日型窗口差异）。
     *
     * @return yyyy-MM-dd
     */
    protected static String nextWeekend() {
        for (int step = 1; step <= 7; step++) {
            java.time.DayOfWeek dow = java.time.LocalDate.now().plusDays(step).getDayOfWeek();
            if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
                return d(step);
            }
        }
        throw new AssertionError("未来 7 天内必有周末，此分支不可达");
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected String json(Object obj) throws Exception {
        return om.writeValueAsString(obj);
    }

    protected MvcResult exec(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        return mvc.perform(builder).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected int code(MvcResult result) throws Exception {
        return body(result).path("code").asInt();
    }

    protected String msg(MvcResult result) throws Exception {
        return body(result).path("message").asText();
    }

    protected JsonNode data(MvcResult result) throws Exception {
        return body(result).path("data");
    }

    protected String login(String username, String password) throws Exception {
        Map<String, String> req = new HashMap<>();
        req.put("username", username);
        req.put("password", password);
        MvcResult result = exec(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)));
        assertEquals(0, code(result), "登录失败：" + body(result).toString());
        return data(result).path("token").asText();
    }

    /**
     * 注册一个全新的普通用户并返回其 Token。
     *
     * <p>用于隔离用例：避免「同一用户时段重叠 4092」在用例之间相互干扰。
     */
    protected String newUser() throws Exception {
        // 后端限制：3~20 位字母/数字/下划线（UUID 里的 '-' 必须去掉）
        String username = "qa" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Map<String, Object> req = new HashMap<>();
        req.put("username", username);
        req.put("password", "123456");
        req.put("confirmPassword", "123456");
        req.put("realName", "QA" + username);
        MvcResult result = exec(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req)));
        if (code(result) != 0) {
            throw new AssertionError("注册测试用户失败：" + result.getResponse().getContentAsString());
        }
        return login(username, "123456");
    }

    protected Long seatId(long floorId, String seatNo) throws Exception {
        MvcResult result = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", LocalDate.now().plusDays(3).toString())
                .param("slot", String.valueOf(slot(14)))
                .header("Authorization", bearer(u1)));
        assertEquals(0, code(result), "平面图查询失败");
        for (JsonNode seat : data(result).path("seats")) {
            if (seatNo.equals(seat.path("seatNo").asText())) {
                return seat.path("seatId").asLong();
            }
        }
        throw new AssertionError("未找到座位 " + seatNo);
    }

    /** 提交预约，返回原始 MvcResult（由调用方断言 code）。 */
    protected MvcResult reserveRaw(String token, long seatId, String date, int startSlot, Integer duration)
            throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("seatId", seatId);
        req.put("date", date);
        req.put("startSlot", startSlot);
        if (duration != null) {
            req.put("duration", duration);
        }
        return exec(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(req))
                .header("Authorization", bearer(token)));
    }

    protected JsonNode reserveOk(String token, long seatId, String date, int startSlot, Integer duration)
            throws Exception {
        MvcResult result = reserveRaw(token, seatId, date, startSlot, duration);
        assertEquals(0, code(result), "预约应成功，实际：" + body(result).toString());
        return data(result);
    }

    /** 管理员：切换楼层状态。 */
    protected MvcResult setFloorStatus(long floorId, String status) throws Exception {
        return exec(put("/api/admin/floors/" + floorId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", status)))
                .header("Authorization", bearer(admin)));
    }

    /** 管理员：切换座位状态。 */
    protected MvcResult setSeatStatus(long seatId, String status) throws Exception {
        return exec(put("/api/admin/seats/" + seatId + "/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", status)))
                .header("Authorization", bearer(admin)));
    }

    /** 管理员：强制释放。 */
    protected MvcResult release(long reservationId, String reason) throws Exception {
        return exec(post("/api/admin/reservations/" + reservationId + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason)))
                .header("Authorization", bearer(admin)));
    }

    /** 管理员：删除座位。 */
    protected MvcResult deleteSeat(long seatId) throws Exception {
        return exec(delete("/api/admin/seats/" + seatId).header("Authorization", bearer(admin)));
    }

    /** 管理员：删除楼层。 */
    protected MvcResult deleteFloor(long floorId) throws Exception {
        return exec(delete("/api/admin/floors/" + floorId).header("Authorization", bearer(admin)));
    }

    protected static String d(int offset) {
        return LocalDate.now().plusDays(offset).toString();
    }

    /**
     * 取某日某格上第一个「空闲可约」的座位 ID。
     *
     * <p>夹具以前靠"约到 3~5 天后"来躲开种子数据占用的格子，V2 起分区口径把窗口收紧到 2 天以内，
     * 那条路不再通用；与其给每个用例挑一个不会撞车的座位号，不如直接问平面图要一个空的。
     *
     * @param floorId 楼层
     * @param date    yyyy-MM-dd
     * @param slot    起始时段格
     * @return 空闲座位 ID
     */
    protected long freeSeatOn(long floorId, String date, int slot) throws Exception {
        MvcResult result = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(result), "平面图查询失败：" + body(result));
        for (JsonNode seat : data(result).path("seats")) {
            if ("FREE".equals(seat.path("displayStatus").asText())) {
                return seat.path("seatId").asLong();
            }
        }
        throw new AssertionError(date + " 第 " + slot + " 格没有空闲座位，用例需要换时段");
    }

    protected String displayStatus(long floorId, long seatId, String date, int slot) throws Exception {
        MvcResult result = exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1)));
        assertEquals(0, code(result));
        for (JsonNode seat : data(result).path("seats")) {
            if (seat.path("seatId").asLong() == seatId) {
                return seat.path("displayStatus").asText();
            }
        }
        throw new AssertionError("平面图里找不到座位 " + seatId);
    }
}

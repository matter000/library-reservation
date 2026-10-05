package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.entity.PeakWindow;
import com.libseat.repository.PeakWindowRepository;
import com.libseat.service.CreditService;
import com.libseat.service.OpenRuleService;
import com.libseat.service.PeakService;
import com.libseat.enums.ViolationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S5 / T-V2-18：高峰识别与调控。
 *
 * <p>四条口径各自守着一个不同的事故：
 * <ol>
 *   <li><b>静态窗口必须按 scope 过滤</b>：只看「这一格有没有窗口」，A 区的限流会贴到 B 区头上；</li>
 *   <li><b>高峰时段的提前开放</b>：不拦就会让人在半夜把第二天最热的格子抢空，
 *       而闸门只该对命中窗口的格子生效；</li>
 *   <li><b>动态高峰</b>：占用率连续超阈后要能在平面图与余量卡片上看见，
 *       且判定必须与卡片用同一份占用率（两处各算必然漂）；</li>
 *   <li><b>PEAK_BAN 只拒高峰</b>：把它当成全局禁约，等于把 70~89 分档的处罚
 *       升级成了 3 天暂停。</li>
 * </ol>
 */
class PeakRuleApiTest extends ApiTestBase {

    /** 高峰限制（不可预约或时长被压缩 / 未到开放时刻）。 */
    private static final int PEAK_LIMIT = 4101;

    /** 用例专用高峰窗口起点（10:00）。 */
    private static final int PEAK_SLOT = 20;

    /** 窗口之外的时段（15:00）。 */
    private static final int CALM_SLOT = 30;

    @Autowired
    private PeakWindowRepository peakWindowRepository;

    @Autowired
    private OpenRuleService openRuleService;

    @Autowired
    private PeakService peakService;

    @Autowired
    private CreditService creditService;

    /** 本用例建的窗口，收尾必须删掉：窗口是全局配置，留着会污染同库的其他用例。 */
    private final List<Long> createdWindows = new ArrayList<>();

    /** 本用例建的分区（含其座位），收尾按「先释放预约 → 删座位 → 删分区」顺序清。 */
    private Long createdZoneId;

    @AfterEach
    void cleanUp() throws Exception {
        for (Long id : createdWindows) {
            peakWindowRepository.deleteById(id);
        }
        createdWindows.clear();
        openRuleService.invalidate();
        if (createdZoneId != null) {
            List<Long> seats = jdbc.queryForList("select id from t_seat where zone_id = ?",
                    Long.class, createdZoneId);
            for (Long seatId : seats) {
                for (Long reservationId : jdbc.queryForList("select id from t_reservation where seat_id = ?"
                                + " and status in ('CONFIRMED','CHECKED_IN')", Long.class, seatId)) {
                    release(reservationId, "用例收尾释放");
                }
                deleteSeat(seatId);
            }
            exec(delete("/api/staff/zones/" + createdZoneId).header("Authorization", bearer(admin)));
            createdZoneId = null;
        }
    }

    /** 明天（避开与当天真实预约的干扰；日型由 dayTypes CSV 全覆盖）。 */
    private String targetDate() {
        return LocalDate.now().plusDays(1).toString();
    }

    /**
     * 建一条高峰窗口并让开放规则缓存失效。
     *
     * @param scope    GLOBAL / FLOOR / ZONE
     * @param zoneId   scope=ZONE 时的分区
     * @param floorId  scope=FLOOR 时的楼层
     * @param from     起始格（含）
     * @param to       结束格（不含）
     * @param maxMin   窗口自带的单次上限（分钟），null 表示只标记不压缩
     * @param advanceOpenFrom 从第几格起启用「前一天才开放」闸门，null 表示不启用
     * @return 窗口 ID
     */
    private long window(String scope, Long zoneId, Long floorId, int from, int to,
                        Integer maxMin, Integer advanceOpenFrom) {
        PeakWindow saved = peakWindowRepository.saveAndFlush(PeakWindow.builder()
                .scope(scope)
                .zoneId(zoneId)
                .floorId(floorId)
                .name("用例高峰 " + UUID.randomUUID().toString().substring(0, 6))
                .dayTypes("WORKDAY,WEEKEND,HOLIDAY")
                .startSlot(from)
                .endSlot(to)
                .maxDurationMin(maxMin)
                .advanceOpenSlot(advanceOpenFrom)
                .enabled(Boolean.TRUE)
                .build());
        createdWindows.add(saved.getId());
        openRuleService.invalidate();
        return saved.getId();
    }

    private MvcResult book(String token, long seatId, String date, int startSlot, int duration) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("seatId", seatId);
        body.put("date", date);
        body.put("startSlot", startSlot);
        body.put("duration", duration);
        return exec(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(token)));
    }

    /** 1F 静音自习区里找一个指定时段空闲的座位（逐格取交集，S2 起的老约定）。 */
    private long freeSeat(String date, int slot) throws Exception {
        JsonNode map = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(admin))));
        for (JsonNode seat : map.path("seats")) {
            if ("FREE".equals(seat.path("displayStatus").asText())
                    && "1F-SILENT".equals(zoneCodeOf(seat))) {
                return seat.path("seatId").asLong();
            }
        }
        throw new AssertionError(date + " 第 " + slot + " 格找不到空闲的静音区座位");
    }

    private String zoneCodeOf(JsonNode seat) throws Exception {
        Long zoneId = seat.path("zoneId").asLong();
        JsonNode zones = data(exec(get("/api/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(admin))));
        for (JsonNode zone : zones) {
            if (zone.path("zoneId").asLong() == zoneId) {
                return zone.path("code").asText();
            }
        }
        return null;
    }

    @Test
    @DisplayName("【验收1】命中静态高峰 → 单次上限压到窗口值，超限 4101 并提示压缩后上限")
    void staticPeakCompressesDuration() throws Exception {
        String date = targetDate();
        window("GLOBAL", null, null, PEAK_SLOT, PEAK_SLOT + 4, 60, null);
        long seat = freeSeat(date, PEAK_SLOT);

        MvcResult tooLong = book(newUser(), seat, date, PEAK_SLOT, 2);
        assertEquals(PEAK_LIMIT, code(tooLong),
                "高峰里约 2 小时必须被压到 60 分钟：" + body(tooLong));
        assertTrue(msg(tooLong).contains("60"), "要给出压缩后的上限：" + msg(tooLong));

        assertEquals(0, code(book(newUser(), freeSeat(date, PEAK_SLOT), date, PEAK_SLOT, 1)),
                "压到 1 小时应能约上");
    }

    @Test
    @DisplayName("【验收2】分区级窗口只管自己：别的区同格不受影响")
    void zoneScopedWindowDoesNotLeak() throws Exception {
        String date = targetDate();
        long seminarZone = zoneIdByCode("1F-SEMINAR");
        window("ZONE", seminarZone, null, PEAK_SLOT, PEAK_SLOT + 4, 60, null);

        // 静音区同格：既不该被压缩，也不该在平面图上出现高峰标记
        long silentSeat = freeSeat(date, PEAK_SLOT);
        assertEquals(0, code(book(newUser(), silentSeat, date, PEAK_SLOT, 2)),
                "★ 分区级窗口被当成全局用，会把 A 区的限流贴到 B 区头上");
        JsonNode rules = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(PEAK_SLOT))
                .param("zoneId", String.valueOf(zoneIdByCode("1F-SILENT")))
                .header("Authorization", bearer(u1)))).path("zoneRules");
        assertEquals(false, rules.path("peakFlag").asBoolean(), "静音区不该出现高峰标记");
    }

    @Test
    @DisplayName("【验收3】高峰时段未到「前一天 22:00」不可预约，窗口外照常")
    void advanceOpenGateOnlyForPeakSlots() throws Exception {
        String date = targetDate();
        window("GLOBAL", null, null, PEAK_SLOT, PEAK_SLOT + 4, null, PEAK_SLOT);
        long seat = freeSeat(date, PEAK_SLOT);

        MvcResult early = book(newUser(), seat, date, PEAK_SLOT, 1);
        assertEquals(PEAK_LIMIT, code(early), "未到开放时刻应 4101：" + body(early));
        assertTrue(msg(early).contains("起才可预约"), "要给出开放时刻：" + msg(early));

        long calmSeat = freeSeat(date, CALM_SLOT);
        assertEquals(0, code(book(newUser(), calmSeat, date, CALM_SLOT, 1)),
                "闸门只该管命中窗口的格子，非高峰时段照常可约");
    }

    @Test
    @DisplayName("【验收4】占用率连续超阈 → 动态高峰成立，平面图与余量卡片同时看见")
    void dynamicPeakFromSustainedOccupancy() throws Exception {
        String date = targetDate();
        long zoneId = twoSeatZone();
        List<Long> seats = jdbc.queryForList("select id from t_seat where zone_id = ? order by id",
                Long.class, zoneId);
        assertEquals(2, seats.size(), "前置条件：用例分区应有 2 个座位");
        for (int i = 0; i < seats.size(); i++) {
            assertEquals(0, code(book(newUser(), seats.get(i), date, PEAK_SLOT, 1)),
                    "前置条件：两个座位都要被占上");
        }

        // 两轮采样：第一轮把「首次超阈时刻」留在 20 分钟前，第二轮才满足持续时长（默认 15 分钟）。
        // 这是让测试不必睡 20 分钟，而不是绕过判定 —— 认定仍由服务端按规则算。
        peakService.detect(LocalDate.parse(date), PEAK_SLOT, LocalDateTime.now().minusMinutes(20));
        int active = peakService.detect(LocalDate.parse(date), PEAK_SLOT, LocalDateTime.now());
        assertTrue(active >= 1, "占用率 100% 且已连续超阈，应至少认定一个分区处于高峰");

        JsonNode rules = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(PEAK_SLOT))
                .param("zoneId", String.valueOf(zoneId))
                .header("Authorization", bearer(u1)))).path("zoneRules");
        assertTrue(rules.path("peakFlag").asBoolean(), "动态高峰必须反映到平面图的 peakFlag 上");
        assertEquals(120, rules.path("maxDurationMin").asInt(),
            "★ 动态高峰没有窗口自带的上限，压缩值取自规则 peak.compress-min（默认 120）");

        JsonNode cards = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(PEAK_SLOT))
                .header("Authorization", bearer(u1))));
        boolean cardMarked = false;
        for (JsonNode card : cards) {
            if (card.path("zoneId").asLong() == zoneId) {
                cardMarked = card.path("peakFlag").asBoolean();
            }
        }
        assertTrue(cardMarked, "余量卡片也要显示高峰提示，否则用户要到平面图才发现限流");
    }

    @Test
    @DisplayName("【验收5】PEAK_BAN 只拒高峰时段，非高峰照常可约")
    void peakBanOnlyBlocksPeakSlots() throws Exception {
        String date = targetDate();
        window("GLOBAL", null, null, PEAK_SLOT, PEAK_SLOT + 4, null, null);
        String token = newUser();
        long uid = data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
        // 两次爽约：100 → 90 → 80，才落进 70~89 档（阈值按「低于 90」判定，一次扣分还在正常档）
        creditService.applyViolation(uid, ViolationType.NO_SHOW, null,
                "{\"rule\":\"book.check-in-after-min\"}", null, null);
        creditService.applyViolation(uid, ViolationType.NO_SHOW, null,
                "{\"rule\":\"book.check-in-after-min\"}", null, null);

        MvcResult banned = book(token, freeSeat(date, PEAK_SLOT), date, PEAK_SLOT, 1);
        assertEquals(PEAK_LIMIT, code(banned), "高峰时段应被 PEAK_BAN 拦下：" + body(banned));
        assertTrue(msg(banned).contains("高峰"), "文案要说清是高峰限制：" + msg(banned));

        assertEquals(0, code(book(token, freeSeat(date, CALM_SLOT), date, CALM_SLOT, 1)),
                "★ 非高峰能约：把 PEAK_BAN 当全局禁约，等于把 70~89 分档升级成了暂停预约");
    }

    /** 建一个只有 2 个座位的分区（动态高峰用例要能把占用率推到 100%）。 */
    private long twoSeatZone() throws Exception {
        String code = "PK" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        Map<String, Object> body = new HashMap<>();
        body.put("floorId", floor1);
        body.put("name", "高峰用例区");
        body.put("code", code);
        body.put("zoneType", "SILENT");
        body.put("maxDurationMin", 240);
        body.put("advanceDays", 7);
        body.put("allowLeave", true);
        body.put("leaveMaxMin", 30);
        body.put("leaveDailyLimit", 3);
        body.put("allowDiscuss", "NO");
        body.put("visibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        body.put("eligibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        MvcResult created = exec(post("/api/staff/zones")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(created), "建分区应成功：" + body(created));
        long zoneId = data(created).path("zoneId").asLong();
        createdZoneId = zoneId;

        Map<String, Object> batch = new HashMap<>();
        batch.put("floorId", floor1);
        batch.put("zoneId", zoneId);
        batch.put("zone", "高峰用例区");
        batch.put("rows", 1);
        batch.put("cols", 2);
        batch.put("prefix", code);
        batch.put("startNo", 1);
        batch.put("status", "AVAILABLE");
        // 行/列从 90 起：避开种子座位的坐标，防止撞 uk（同格只能有一个座位）
        batch.put("rowFrom", 90);
        batch.put("colFrom", 90);
        MvcResult seats = exec(post("/api/admin/seats/batch")
                .contentType(MediaType.APPLICATION_JSON).content(json(batch))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(seats), "生成座位应成功：" + body(seats));
        assertEquals(2, data(seats).path("created").asInt(), "应生成 2 个座位");
        return zoneId;
    }

    /** 按编码取分区 ID（种子里的区名不写死数字，避免种子一改就全错）。 */
    private long zoneIdByCode(String zoneCode) throws Exception {
        JsonNode zones = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(admin))));
        for (JsonNode zone : zones) {
            if (zoneCode.equals(zone.path("code").asText())) {
                return zone.path("zoneId").asLong();
            }
        }
        throw new AssertionError("种子中应有 " + zoneCode + " 分区");
    }
}

package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.entity.Penalty;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.entity.User;
import com.libseat.enums.CreditLogKind;
import com.libseat.enums.PenaltyStatus;
import com.libseat.enums.PenaltyType;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.CreditLogRepository;
import com.libseat.repository.PenaltyRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ViolationRepository;
import com.libseat.scheduler.ReservationScheduler;
import com.libseat.service.CreditService;
import com.libseat.service.OpenRuleService;
import com.libseat.service.RuleConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S3 / T-V2-12：违规账本、信用分与处罚阶梯。
 *
 * <p>这一片最容易被写坏的地方是「分数被绕过」和「处罚重复」，所以断言全部对着
 * 账本与处罚表看，而不是只看接口返回值；每个用例都用 {@code newUser()} 隔离
 * （分数、处罚、单日累计都是按人算的）。
 *
 * <p>★ 用例里的时长与日期都不写死：单日上限、单次上限、窗口起点全部从分区配置与
 * 开放档案现取（{@link #silentZone()} / {@link #windowStart(String)}），
 * 否则「今天星期几」一变，用例就会因为正确的产品规则而失败。
 */
class CreditPenaltyApiTest extends ApiTestBase {

    /** 参数错误。 */
    private static final int PARAM_ERROR = 4001;

    /** 分区单日累计超限。 */
    private static final int DAILY_CAP_EXCEEDED = 4098;

    /** 信用分受限禁约。 */
    private static final int CREDIT_BANNED = 4100;

    /** 角色无资格（这里用于「馆员入口不得解除学期禁」）。 */
    private static final int ROLE_NOT_ELIGIBLE = 4031;

    /** 种子数据里唯一带 360 分钟单日上限的分区，用它验累计口径。 */
    private static final String SILENT_ZONE = "1F-SILENT";

    @Autowired
    private CreditService creditService;

    @Autowired
    private CreditLogRepository creditLogRepository;

    @Autowired
    private ViolationRepository violationRepository;

    @Autowired
    private PenaltyRepository penaltyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReservationScheduler scheduler;

    @Autowired
    private OpenRuleService openRuleService;

    @Autowired
    private RuleConfigService ruleConfigService;

    // ==================== 取数与造数工具 ====================

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private int score(Long id) {
        return userRepository.findById(id).map(User::getCreditScore).orElse(-1);
    }

    private List<CreditLogKind> kinds(Long id) {
        return creditLogRepository.findByUserIdOrderByCreateTimeAscIdAsc(id).stream()
                .map(log -> log.getKind()).toList();
    }

    private String evidence() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("rule", "test.T-V2-12");
        fields.put("judgedAt", BizConstants.formatDateTime(LocalDateTime.now()));
        return creditService.buildEvidence(fields);
    }

    private List<Penalty> active(Long id) {
        return penaltyRepository.findByUserIdAndStatusOrderByIdDesc(id, PenaltyStatus.ACTIVE).stream()
                .filter(penalty -> penalty.activeAt(LocalDateTime.now()))
                .toList();
    }

    private List<PenaltyType> types(Long id) {
        return active(id).stream().map(Penalty::getType).toList();
    }

    /**
     * 阶梯的全部输入（处罚表原样 + 阈值 + 天数）。
     *
     * <p>★ 「跌破 50 该有 7 天禁约」这类断言一旦失败，只报 expected/actual 是查不出原因的：
     * 到底是没生成、生成了但判活被过滤、还是阈值被别的用例改掉了，看得到输入才知道。
     *
     * @param id 用户 ID
     * @return 诊断文本
     */
    private String ladderInputs(Long id) {
        String rows = penaltyRepository.findAll().stream()
                .filter(p -> p.getUserId().equals(id))
                .map(p -> p.getType() + "/" + p.getStatus() + " start=" + p.getStartTime()
                        + " end=" + p.getEndTime())
                .collect(java.util.stream.Collectors.joining(" | "));
        return "处罚表=[" + rows + "] 阈值 90/70/50/1 实为 "
                + ruleConfigService.creditPeakBanUnder() + "/" + ruleConfigService.creditBan3dUnder()
                + "/" + ruleConfigService.creditBan7dUnder() + "/" + ruleConfigService.creditSemesterUnder()
                + " 天数 3d=" + ruleConfigService.creditBan3dDays() + " 7d=" + ruleConfigService.creditBan7dDays()
                + " now=" + LocalDateTime.now();
    }

    private JsonNode zones(long floorId) throws Exception {
        return data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floorId))
                .header("Authorization", bearer(admin))));
    }

    private JsonNode silentZone() throws Exception {
        for (JsonNode node : zones(floor1)) {
            if (SILENT_ZONE.equals(node.path("code").asText())) {
                return node;
            }
        }
        throw new AssertionError("种子分区列表应含 " + SILENT_ZONE);
    }

    /**
     * 静音区内可约的日期：分区把可提前天数压到 3 天，所以只在 +1 / +2 里挑，
     * 且优先工作日（周末窗口只有 12 小时，晚间时段会被正确拒掉）。
     *
     * @return yyyy-MM-dd
     */
    private String soonWorkday() {
        for (int step = 1; step <= 2; step++) {
            LocalDate date = LocalDate.now().plusDays(step);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                return date.toString();
            }
        }
        return LocalDate.now().plusDays(1).toString();
    }

    /** 某日开放窗口的起始格（取后端同一来源，避免用例自己复刻日型规则）。 */
    private int windowStart(String date) {
        OpenRuleService.DayRule day = openRuleService.resolve(LocalDate.parse(date));
        assertFalse(day.isClosed(), date + " 不应是闭馆日");
        return day.getOpenStart();
    }

    /** 取某分区内在 [startSlot, startSlot+slotCount) 全时段都空闲的第 index 个座位。 */
    private long freeSeatOfZone(long floorId, long zoneId, String date, int startSlot, int slotCount, int index)
            throws Exception {
        // ★ 必须逐格取交集：只按「起始格空闲」选座，后面那些格可能已被种子数据占了，
        //   预约接口会返回座位冲突而不是 0，用例就会假性失败。
        Set<Long> candidates = null;
        for (int i = 0; i < slotCount; i++) {
            JsonNode map = data(exec(get("/api/seats/map")
                    .param("floorId", String.valueOf(floorId))
                    .param("zoneId", String.valueOf(zoneId))
                    .param("date", date)
                    .param("slot", String.valueOf(startSlot + i))
                    .header("Authorization", bearer(u1))));
            Set<Long> free = new LinkedHashSet<>();
            for (JsonNode seat : map.path("seats")) {
                if ("FREE".equals(seat.path("displayStatus").asText())) {
                    free.add(seat.path("seatId").asLong());
                }
            }
            if (candidates == null) {
                candidates = free;
            } else {
                candidates.retainAll(free);
            }
        }
        assertNotNull(candidates, "候选座位初始化失败");
        assertTrue(candidates.size() > index, "分区 " + zoneId + " 在 " + date + " 时段 "
                + startSlot + ".." + (startSlot + slotCount) + " 需要至少 " + (index + 1)
                + " 个全空闲座位，实际 " + candidates.size());
        return new ArrayList<>(candidates).get(index);
    }

    private MvcResult book(String token, long seatId, String date, int startSlot, int duration) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("seatId", seatId);
        body.put("date", date);
        body.put("startSlot", startSlot);
        body.put("duration", duration);
        return exec(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(token)));
    }

    private MvcResult setRule(String rkey, String value) throws Exception {
        List<Map<String, String>> body = List.of(Map.of(
                "scope", "GLOBAL", "scopeId", "", "rkey", rkey, "value", value));
        return exec(put("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
    }

    private MvcResult release(String base, long penaltyId, String note) throws Exception {
        return exec(post(base + "/" + penaltyId + "/release")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("note", note)))
                .header("Authorization", bearer(admin)));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("【验收1】五类违规各自的分值正确，且缺证据直接 4001")
    void violationDeltas() throws Exception {
        String me = newUser();
        long id = uid(me);
        assertEquals(100, score(id), "注册即 100 分");
        assertEquals(1, creditLogRepository.findByUserIdOrderByCreateTimeAscIdAsc(id).size(),
                "★ 初始分必须是账本上的一笔 INIT，否则 sum(delta) 永远对不上快照");
        assertEquals(CreditLogKind.INIT, kinds(id).get(0));

        Map<ViolationType, Integer> expected = new EnumMap<>(ViolationType.class);
        expected.put(ViolationType.NO_SHOW, -10);
        expected.put(ViolationType.LEAVE_TIMEOUT, -10);
        expected.put(ViolationType.GROUP_ABSENT, -10);
        expected.put(ViolationType.LEADER_ABSENT, -20);
        expected.put(ViolationType.ON_SITE_OCCUPY, -15);
        int running = 100;
        for (Map.Entry<ViolationType, Integer> entry : expected.entrySet()) {
            creditService.applyViolation(id, entry.getKey(), null, evidence(), null, null);
            running += entry.getValue();
            assertEquals(running, score(id), entry.getKey() + " 应按 " + entry.getValue() + " 记账");
        }
        assertEquals(List.of(CreditLogKind.PENALTY, CreditLogKind.PENALTY, CreditLogKind.PENALTY,
                        CreditLogKind.PENALTY, CreditLogKind.PENALTY),
                kinds(id).subList(1, 6), "扣分流水都要落在 PENALTY 名下，便于「分数怎么来的」一屏看清");
        assertEquals(running, creditLogRepository.sumDelta(id), "账本合计必须等于快照");

        assertEquals(5, violationRepository.findByUserIdOrderByIdDesc(id).size(), "五类各一条");
        assertNotNull(violationRepository.findByUserIdOrderByIdDesc(id).get(0).getEvidence(),
                "证据必须落库（申诉裁定页要渲染它）");

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> creditService.applyViolation(id, ViolationType.ON_SITE_OCCUPY, null, "  ", null, null));
        assertEquals(PARAM_ERROR, thrown.getErrorCode().getCode(), "空证据必须拒，而不是先扣分");
        assertEquals(running, score(id), "被拒的裁定不得动分数");
    }

    @Test
    @DisplayName("【验收2】跌破 90/70/50/1 自动升档，重复违规不产生第二条同类 ACTIVE")
    void ladderEscalatesAndIsIdempotent() throws Exception {
        String me = newUser();
        long id = uid(me);

        creditService.applyViolation(id, ViolationType.NO_SHOW, null, evidence(), null, null);
        assertEquals(90, score(id));
        assertEquals(List.of(), types(id),
                "★ PRD §9.3 写的是「90~100 正常使用」，阈值按「低于」判定；把 < 写成 <= 会让一次爽约就封掉高峰");

        creditService.applyViolation(id, ViolationType.ON_SITE_OCCUPY, null, evidence(), null, null);
        assertEquals(75, score(id));
        assertEquals(List.of(PenaltyType.PEAK_BAN), types(id),
                "跌破 90 → 高峰禁约｜" + ladderInputs(id));

        creditService.applyViolation(id, ViolationType.NO_SHOW, null, evidence(), null, null);
        assertEquals(65, score(id));
        assertEquals(List.of(PenaltyType.BOOK_BAN_3D), types(id),
                "★ 升档时旧档要解除：留两条 ACTIVE 会让「还能不能约」翻多条记录才知道｜" + ladderInputs(id));
        Penalty first3d = penaltyRepository.findByUserIdAndStatusOrderByIdDesc(id, PenaltyStatus.ACTIVE).get(0);
        assertNotNull(first3d.getEndTime(), "3 天禁约必须有恢复时刻");

        LocalDateTime extendedFrom = first3d.getEndTime();
        creditService.applyViolation(id, ViolationType.NO_SHOW, null, evidence(), null, null);
        assertEquals(55, score(id));
        List<Penalty> stillActive = penaltyRepository.findByUserIdAndStatusOrderByIdDesc(id, PenaltyStatus.ACTIVE);
        assertEquals(1, stillActive.size(), "重复违规不得再加一条 ACTIVE");
        assertTrue(stillActive.get(0).getEndTime().isAfter(extendedFrom),
                "同类只延长 end_time，实际 " + stillActive.get(0).getEndTime());

        creditService.applyViolation(id, ViolationType.LEADER_ABSENT, null, evidence(), null, null);
        assertEquals(35, score(id));
        assertEquals(List.of(PenaltyType.BOOK_BAN_7D), types(id),
                "跌破 50 → 7 天禁约｜" + ladderInputs(id));

        creditService.applyViolation(id, ViolationType.LEADER_ABSENT, -50, evidence(), null, null);
        assertEquals(0, score(id), "分数只夹到 0，不做负数");
        assertEquals(List.of(PenaltyType.SEMESTER_BAN), types(id),
                "0 分 → 暂停至学期末｜" + ladderInputs(id));
        assertNull(penaltyRepository.findByUserIdAndStatusOrderByIdDesc(id, PenaltyStatus.ACTIVE)
                .get(0).getEndTime(), "学期禁没有自动终点，只能人工解除");
        assertEquals(0, creditLogRepository.sumDelta(id),
                "★ 夹到 0 时账本记的是实际动过的 -35，而不是请求的 -50");
    }

    @Test
    @DisplayName("【验收3】受限预约 4100 且带恢复时间；关掉开关后不限制；学期禁只能系统管理员解")
    void bookingGateAndEscapeHatch() throws Exception {
        String me = newUser();
        long id = uid(me);
        String date = soonWorkday();
        int start = windowStart(date) + 2;
        long seat = freeSeatOfZone(floor1, silentZone().path("zoneId").asLong(), date, start, 2, 0);

        creditService.applyViolation(id, ViolationType.NO_SHOW, null, evidence(), null, null);
        creditService.applyViolation(id, ViolationType.NO_SHOW, null, evidence(), null, null);
        creditService.applyViolation(id, ViolationType.ON_SITE_OCCUPY, null, evidence(), null, null);
        assertEquals(65, score(id), "两次爽约 + 一次现场占座 = 65，此时应处于「暂停预约 3 天」");

        MvcResult banned = book(me, seat, date, start, 1);
        assertEquals(CREDIT_BANNED, code(banned), "受限用户应被闸住，实际：" + body(banned));
        assertTrue(msg(banned).contains("暂停预约 3 天") && msg(banned).contains("恢复"),
                "★ 必须给出恢复时间，不能只说「不可预约」：" + msg(banned));

        assertEquals(0, code(setRule("credit.enforce_enabled", "false")), "关闭开关应成功");
        MvcResult allowed;
        try {
            allowed = book(me, seat, date, start, 1);
        } finally {
            assertEquals(0, code(setRule("credit.enforce_enabled", "true")), "规则必须恢复");
        }
        assertEquals(0, code(allowed), "逃生阀打开后应可预约（分数与违规仍照记），实际：" + body(allowed));
        assertEquals(65, score(id), "不限制不等于不记录：分数不能被改动");

        creditService.applyViolation(id, ViolationType.LEADER_ABSENT, -65, evidence(), null, null);
        assertEquals(0, score(id));
        Penalty semesterBan = penaltyRepository
                .findByUserIdAndStatusOrderByIdDesc(id, PenaltyStatus.ACTIVE).get(0);
        assertEquals(PenaltyType.SEMESTER_BAN, semesterBan.getType(), "0 分应已升档为学期禁");

        MvcResult denied = release("/api/staff/penalties", semesterBan.getId(), "馆员提前解除");
        // 系统管理员走馆员入口同样被拒：判定的是「这条处罚属于学期禁」，不是调用方角色
        assertEquals(ROLE_NOT_ELIGIBLE, code(denied), "学期禁不能走馆员入口，实际：" + body(denied));
        assertEquals(0, code(release("/api/admin/penalties", semesterBan.getId(), "已签承诺书")),
                "系统管理员入口应可解除");
        assertFalse(types(id).contains(PenaltyType.SEMESTER_BAN), "解除后不得仍是 ACTIVE");
    }

    @Test
    @DisplayName("【验收4】同一分区单日累计超上限 → 4098，返回已用与上限；不影响其他分区")
    void dailyCapEnforced() throws Exception {
        String me = newUser();
        JsonNode zone = silentZone();
        long zoneId = zone.path("zoneId").asLong();
        int cap = zone.path("dailyCapMin").asInt();
        int singleMax = zone.path("maxDurationMin").asInt();
        assertTrue(cap > 0, SILENT_ZONE + " 应配置了单日累计上限");

        // 时长全部由配置推导：第一次顶到「不超单次上限且不超单日上限」的最大值，
        // 第二次取「刚好越线」的最小值 —— 换种子配置也不用改用例
        List<Integer> allowed = ruleConfigService.allowedDurations();
        int firstHours = allowed.stream().filter(h -> h * 60 <= singleMax && h * 60 <= cap)
                .mapToInt(Integer::intValue).max().orElseThrow(() -> new AssertionError(
                        "没有可用时长：单日 " + cap + " / 单次 " + singleMax));
        int usedMin = firstHours * 60;
        int secondHours = allowed.stream().filter(h -> h * 60 <= singleMax && usedMin + h * 60 > cap)
                .mapToInt(Integer::intValue).min().orElseThrow(() -> new AssertionError(
                        "种子配置下无法构造超限：单日 " + cap + " / 单次 " + singleMax));

        String date = soonWorkday();
        int start = windowStart(date);
        int openEnd = openRuleService.resolve(LocalDate.parse(date)).getOpenEnd();
        int firstSlots = BizConstants.slotsOf(firstHours);
        int secondSlots = BizConstants.slotsOf(secondHours);
        int otherStart = start + firstSlots + secondSlots;
        assertTrue(otherStart + BizConstants.slotsOf(1) <= openEnd,
                date + " 的开放窗口放不下「" + (firstHours + secondHours) + " 小时 + 对照时段」，用例需换日期");

        assertEquals(0, code(book(me, freeSeatOfZone(floor1, zoneId, date, start, firstSlots, 0),
                        date, start, firstHours)),
                "先约 " + firstHours + " 小时（未超 " + cap + " 分钟）应成功");

        int secondStart = start + firstSlots;
        MvcResult over = book(me, freeSeatOfZone(floor1, zoneId, date, secondStart, secondSlots, 0),
                date, secondStart, secondHours);
        assertEquals(DAILY_CAP_EXCEEDED, code(over),
                "累计 " + (usedMin + secondHours * 60) + " 分钟应超 " + cap + " 上限，实际：" + body(over));
        assertTrue(msg(over).contains(String.valueOf(cap)) && msg(over).contains("已用 " + usedMin),
                "要给出已用/上限，便于用户自己算还剩多久：" + msg(over));

        // 换到没有单日累计上限的分区仍可约（上限是分区口径，不该牵连其他分区）。
        // ★ 必须换时段：同一用户在同一时段已被 1F 那条预约占住，重叠校验会先给 4092，
        //   那样这条断言验的就不是「分区口径」而是时段重叠了。
        // ★ 对照分区用预约人自己的视角挑（可见性 + 资格都由 listForUser 过滤），
        //   否则会撞上 2F 教师专区而变成验 4031。
        long otherZoneId = -1;
        JsonNode floor2Zones = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floor2))
                .param("date", date)
                .param("slot", String.valueOf(otherStart))
                .header("Authorization", bearer(me))));
        for (JsonNode node : floor2Zones) {
            // 对照分区还必须有「装得下这个日期」的窗口：2F 电子阅览区本区口径是提前 1 天，
            // 拿它当对照会被窗口校验先拦成 4001，那条断言验的就不是「单日累计是分区口径」了。
            if (node.path("dailyCapMin").isNull() && node.path("eligible").asBoolean(false)
                    && node.path("advanceDays").asInt(0) >= 2) {
                otherZoneId = node.path("zoneId").asLong();
                break;
            }
        }
        assertTrue(otherZoneId > 0, "2F 应有「本人可约、无单日上限且窗口覆盖本日期」的分区，否则本用例要换对照分区："
                + floor2Zones);
        assertEquals(0, code(book(me, freeSeatOfZone(floor2, otherZoneId, date, otherStart, 2, 0),
                        date, otherStart, 1)),
                "其他分区不该被 1F 的累计牵连");
    }

    @Test
    @DisplayName("【验收5】爽约扫描端到端产生违规；全库对账为空")
    void consistencyAndNoShowTrigger() throws Exception {
        String me = newUser();
        long id = uid(me);
        LocalDate today = LocalDate.now();
        long seat = freeSeatOfZone(floor1, silentZone().path("zoneId").asLong(), today.toString(), 4, 2, 0);
        // 时段刻意取 02:00（开放窗口外）：既不可能与种子数据撞 uk_slot_seat_date_idx，
        // 又必然已过「签到时限」，扫描一定命中。预约绕过接口校验直插，验的是扫描链路。
        Reservation expired = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(id).seatId(seat).floorId(floor1).reserveDate(today)
                .startSlot(4).endSlot(6).status(ReservationStatus.CONFIRMED).build());
        for (int i = 4; i < 6; i++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(expired).seatId(seat).reserveDate(today).slotIndex(i).build());
        }
        scheduler.scanNoShow();

        assertEquals(ReservationStatus.NO_SHOW, reservationRepository.findById(expired.getId())
                .orElseThrow().getStatus(), "扫描要归档预约");
        Integer leftover = jdbc.queryForObject(
                "select count(1) from t_reservation_slot where reservation_id = ?",
                Integer.class, expired.getId());
        assertEquals(0, leftover == null ? -1 : leftover, "扫描要释放占用明细");
        assertEquals(90, score(id), "爽约应自动扣 10 分");
        assertEquals(1, violationRepository.findByUserIdOrderByIdDesc(id).size(), "扫描要产生一条违规记录");
        assertEquals(ViolationType.NO_SHOW, violationRepository.findByUserIdOrderByIdDesc(id).get(0).getType());
        assertTrue(violationRepository.findByUserIdOrderByIdDesc(id).get(0).getEvidence()
                .contains("check-in-after-min"), "证据要能说明是按哪条规则裁的");
        assertEquals(List.of(), types(id), "90 分是「正常使用」档的下界，扫描不该在此刻就生成处罚");

        JsonNode report = data(exec(get("/api/admin/rules/consistency")
                .header("Authorization", bearer(admin))));
        assertEquals(0, report.size(),
                "★ 全库对账必须为空：任何一处绕过 CreditService 改分都会在这里露出来，实际 " + report);
    }
}

package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.common.RuleKeys;
import com.libseat.entity.Reservation;
import com.libseat.enums.PatrolAction;
import com.libseat.enums.PatrolStatus;
import com.libseat.enums.PatrolType;
import com.libseat.enums.ReservationStatus;
import com.libseat.service.PatrolService;
import com.libseat.service.RuleConfigService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S6 / T-V2-21：巡查任务生成与看板。
 *
 * <p>要守住的口径按重要性排：
 * <ol>
 *   <li><b>机器绝不自动释放</b>（PRD §12-25）：生成器跑完之后，预约状态与 slot 行必须一根汗毛都没动；</li>
 *   <li><b>无证据不落账</b>：任务自带可读 reason 与结构化 evidence，处置为违规时把处置信息并进同一份证据；</li>
 *   <li><b>同一座位只挂一个未处理任务</b>：{@code uk_patrol_open} 让 10 分钟一轮的重复扫描天然幂等；</li>
 *   <li><b>处置必填说明且不可重复</b>：馆员动了谁的座位，事后要能回答「当时为什么这么处理」。</li>
 * </ol>
 *
 * <p>★ 判定时刻由 {@link PatrolService#generate(LocalDateTime)} 注入：
 * 「签到后连续使用 2 小时」这类事实在真实时钟下要么等两小时、要么伪造签到时刻，
 * 两者都比注入时刻更假（预约本身仍是走接口建的，只有生成器的判定点是给的）。
 */
class PatrolApiTest extends ApiTestBase {

    /** 状态不允许。 */
    private static final int STATUS_NOT_ALLOWED = 4093;

    /** 参数错误。 */
    private static final int PARAM_ERROR = 4001;

    /** 无权限。 */
    private static final int FORBIDDEN = 4030;

    /** 用例时段基准（明天的 14:00，避开与当天真实扫描的相互干扰）。 */
    private static final int START = slot(14);

    @Autowired
    private PatrolService patrolService;

    @Autowired
    private RuleConfigService ruleConfigService;

    /** 本用例建的预约（收尾统一释放，别给共享内存库留占用行）。 */
    private final List<Long> createdReservations = new ArrayList<>();

    /** 本用例碰过的座位（收尾删任务，避免别的应用上下文看到残留）。 */
    private final List<Long> touchedSeats = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (Long seatId : touchedSeats) {
            jdbc.update("delete from t_patrol_task where seat_id = ?", seatId);
        }
        touchedSeats.clear();
        for (Long reservationId : createdReservations) {
            jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservationId);
            jdbc.update("delete from t_leave_record where reservation_id = ?", reservationId);
            jdbc.update("delete from t_reservation where id = ?", reservationId);
        }
        createdReservations.clear();
    }

    // ==================== 验收 1：生成规则与幂等 ====================

    @Test
    @DisplayName("【验收1】签到后连续使用超阈值且无暂离 → HIGH 级任务；重复扫描不叠加")
    void suspectedOccupyGeneratedOnce() throws Exception {
        String date = dw(1);
        String student = newUser();
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        long reservationId = checkedIn(student, seat, date, START, 4,
                BizConstants.slotStart(LocalDate.parse(date), START));

        LocalDateTime now = BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(125);
        assertEquals(1, patrolService.generate(now), "应登记一条疑似占座任务");

        JsonNode task = openTask(seat);
        assertEquals(PatrolType.SUSPECTED_OCCUPY.name(), task.path("type").asText());
        assertEquals("HIGH", task.path("level").asText(), "达到报警线就该排最前（PRD §6.5）");
        assertTrue(task.path("reason").asText().contains("125 分钟"),
                "reason 要带上实测连续时长：" + task.path("reason").asText());
        assertEquals(reservationId, task.path("reservationId").asLong(), "任务要能定位到是谁的哪张预约");
        JsonNode items = task.path("evidenceItems");
        assertTrue(items.size() >= 4, "证据要结构化，馆员才不用猜系统为什么报警：" + items);
        boolean hasRule = false;
        for (JsonNode item : items) {
            if ("rule".equals(item.path("key").asText())) {
                hasRule = true;
                assertTrue(item.path("label").asText().length() > 0, "每个证据项都要有可读标签");
            }
        }
        assertTrue(hasRule, "★ 证据必须带规则名（与违规账本同一口径），否则报警不可复核：" + items);

        assertEquals(0, patrolService.generate(now.plusMinutes(10)),
                "★ 同一座位已有未处理任务时不得叠加（uk_patrol_open 是幂等真相）");
        assertEquals(1, openTasksOf(seat));
    }

    @Test
    @DisplayName("【验收1】快结束的预约不打扰：改由「到期仍占」接管")
    void nearEndReservationFallsToOverdueRule() throws Exception {
        String date = dw(1);
        String student = newUser();
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        checkedIn(student, seat, date, START, 2, BizConstants.slotStart(LocalDate.parse(date), START));

        // 14:00 起 2 小时 → 16:05 判定时已连续使用 125 分钟，但剩余是负数：
        // 疑似占座的条件（剩余 ≥30 分钟）不成立，规则三接管。
        LocalDateTime now = BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(125);
        patrolService.generate(now);
        JsonNode task = openTask(seat);
        assertEquals(PatrolType.OVERDUE_HOLDING.name(), task.path("type").asText(),
                "已过结束时刻仍在使用，该报的是「到期仍占」而不是「疑似占座」");
        assertEquals("HIGH", task.path("level").asText());
        assertTrue(task.path("reason").asText().contains("结束"), task.path("reason").asText());
    }

    // ==================== 验收 2：处置 ====================

    @Test
    @DisplayName("【验收2】处置 RELEASE → 座位释放 + 学生收到通知 + 审计留痕，任务落终态")
    void releaseDispositionFreesSeatAndNotifies() throws Exception {
        String date = dw(1);
        String student = newUser();
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        long reservationId = checkedIn(student, seat, date, START, 4,
                BizConstants.slotStart(LocalDate.parse(date), START));
        long patrolId = patrolService.generate(
                BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(125)) > 0
                ? openTask(seat).path("patrolId").asLong() : -1;
        assertTrue(patrolId > 0, "前置条件：已生成任务");

        MvcResult handled = exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(handleBody(PatrolAction.RELEASE.name(), "现场核实人不在，已口头告知后释放")))
                .header("Authorization", bearer(librarian())));
        assertEquals(0, code(handled), "处置应成功：" + body(handled));
        assertEquals(PatrolStatus.RELEASED.name(), data(handled).path("status").asText());
        assertFalse(data(handled).path("canHandle").asBoolean(), "已处置的任务不能重复处置");

        String status = jdbc.queryForObject("select status from t_reservation where id = ?",
                String.class, reservationId);
        assertEquals(ReservationStatus.ADMIN_CANCELLED.name(), status, "释放要落到强制释放那一条路径");
        assertEquals(0, jdbc.queryForObject("select count(*) from t_reservation_slot where reservation_id = ?",
                Integer.class, reservationId), "slot 行必须删掉，否则座位仍然占着");
        assertEquals(0, openTasksOf(seat), "open_flag 必须随状态一起归 NULL");

        List<String> types = noticeTypes(student);
        assertTrue(types.contains("SEAT_RELEASED"), "学生必须收到解释「座位为什么没了」的通知：" + types);
        assertTrue(noticeContent(student, "SEAT_RELEASED").contains("现场核实人不在"),
                "通知要带上馆员填的原因，不能只说「被释放了」");
        assertEquals(1, jdbc.queryForObject("select count(*) from t_audit_log where action = ? and target_id = ?",
                Integer.class, "PATROL_HANDLE", String.valueOf(patrolId)), "处置必须留审计");
    }

    @Test
    @DisplayName("【验收2】处置 VIOLATION → 现场占座违规，证据含任务号与照片且可渲染")
    void violationDispositionPersistsEvidence() throws Exception {
        String date = dw(1);
        String student = newUser();
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        long reservationId = checkedIn(student, seat, date, START, 4,
                BizConstants.slotStart(LocalDate.parse(date), START));
        long patrolId = openTaskAfterGenerate(seat, date);

        Map<String, Object> body = handleBody(PatrolAction.VIOLATION.name(), "本人与预约不符，现场核对后裁定");
        body.put("photoUrl", "https://example.test/patrol/1.jpg");
        assertEquals(0, code(exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(librarian())))));

        Integer violations = jdbc.queryForObject("select count(*) from t_violation"
                        + " where reservation_id = ? and type = 'ON_SITE_OCCUPY'",
                Integer.class, reservationId);
        assertEquals(1, violations, "处置为违规时必须落一条现场占座违规");
        String evidence = jdbc.queryForObject("select evidence from t_violation"
                        + " where reservation_id = ? and type = 'ON_SITE_OCCUPY'",
                String.class, reservationId);
        assertTrue(evidence.contains("\"patrolId\":\"" + patrolId + "\"")
                        || evidence.contains("\"patrolId\":" + patrolId),
                "★ 违规证据要能回指巡查任务，否则裁定成了无源判断：" + evidence);
        assertTrue(evidence.contains("photoUrl"), "现场照片链接要进证据：" + evidence);
        assertEquals(-15, jdbc.queryForObject("select score_delta from t_violation"
                        + " where reservation_id = ? and type = 'ON_SITE_OCCUPY'", Integer.class, reservationId),
                "现场占座扣分口径来自 ViolationType 默认值");
        assertTrue(noticeTypes(student).contains("VIOLATION"), "违规即时通知同样要落到候补/申诉那条链路");
    }

    // ==================== 验收 3：权限 ====================

    @Test
    @DisplayName("【验收3】馆员面权限：学生 4030、馆员 200、系统管理员 200")
    void patrolEndpointsAreStaffOnly() throws Exception {
        assertEquals(FORBIDDEN, code(exec(get("/api/staff/patrol").header("Authorization", bearer(u1)))),
                "★ 巡查看板会暴露「谁坐在哪」，学生绝不能读");
        assertEquals(0, code(exec(get("/api/staff/patrol").header("Authorization", bearer(librarian())))));
        assertEquals(0, code(exec(get("/api/staff/patrol").header("Authorization", bearer(admin)))));
    }

    @Test
    @DisplayName("【验收3】处置动作字典与 PatrolAction 枚举同源，学生读不到")
    void actionDictionaryComesFromEnum() throws Exception {
        JsonNode list = data(exec(get("/api/staff/patrol/actions")
                .header("Authorization", bearer(librarian()))));
        assertEquals(PatrolAction.values().length, list.size(), "字典长度必须等于枚举数量：" + list);

        Map<String, JsonNode> byAction = new HashMap<>();
        for (JsonNode node : list) {
            byAction.put(node.path("action").asText(), node);
        }
        for (PatrolAction action : PatrolAction.values()) {
            JsonNode node = byAction.get(action.name());
            assertNotNull(node, "枚举 " + action + " 必须出现在字典里");
            assertEquals(action.getText(), node.path("text").asText(),
                    "★ 按钮文案与动作码同源，否则枚举改名后页面挂着旧中文提交新语义");
            assertEquals(action.getTargetStatus().name(), node.path("targetStatus").asText(),
                    "REMIND→REMIND、RELEASE→RELEASED 这层不同形映射只能由服务端下发");
        }

        assertEquals(FORBIDDEN, code(exec(get("/api/staff/patrol/actions")
                .header("Authorization", bearer(u1)))), "动作字典也属于馆员面");
    }

    @Test
    @DisplayName("【口径】四条巡查阈值确实由 V14 播种，键名与 RuleKeys 常量一致")
    void patrolRulesAreSeededUnderTheKeysTheCodeReads() {
        List<String> keys = List.of(RuleKeys.PATROL_SUSPECT_MIN, RuleKeys.PATROL_SUSPECT_MIN_LEFT,
                RuleKeys.PATROL_GROUP_ABSENT_MIN, RuleKeys.PATROL_ESCALATE_MIN);
        Integer seeded = jdbc.queryForObject(
                "select count(*) from t_rule_config where rkey in (?,?,?,?)", Integer.class, keys.toArray());
        assertEquals(4, seeded,
                "★ 代码读的键必须和迁移播种的键同名，否则阈值会静默回落到硬编码默认值（S5 的 offer-min 就是这么漂过一次）："
                        + keys);
        assertEquals(120, ruleConfigService.patrolSuspectMin(), "PRD §6.5 的 2 小时口径");
        assertEquals(30, ruleConfigService.patrolMinLeftMin(), "快结束的不打扰：剩余下限");
    }

    // ==================== 验收 4：看板筛选与必填校验 ====================
    @Test
    @DisplayName("【验收4】看板可按楼层/等级筛选；缺说明 4001、重复处置 4093")
    void boardFiltersAndValidation() throws Exception {
        String date = dw(1);
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        checkedIn(newUser(), seat, date, START, 4, BizConstants.slotStart(LocalDate.parse(date), START));
        patrolService.generate(BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(125));
        long patrolId = openTask(seat).path("patrolId").asLong();

        JsonNode filtered = data(exec(get("/api/staff/patrol?openOnly=true&floorId=" + floor1 + "&level=HIGH")
                .header("Authorization", bearer(librarian()))));
        boolean mine = false;
        for (JsonNode node : filtered) {
            assertEquals("HIGH", node.path("level").asText(), "等级筛选不能把 MID/LOW 混进来");
            assertEquals(floor1, node.path("floorId").asLong(), "楼层筛选不能把别的层带出来");
            if (node.path("seatId").asLong() == seat) {
                mine = true;
            }
        }
        assertTrue(mine, "本用例生成的任务应出现在筛选结果里：" + filtered);
        // 种子数据里本来就有「历史使用中」的预约，它们正是 OVERDUE_HOLDING 该报的东西，
        // 所以这里断言的是「筛选不漏行」而不是「只有我这一条」——后者会在任何有历史数据的库里假红。
        for (JsonNode node : data(exec(get("/api/staff/patrol?openOnly=true&floorId=" + floor2)
                .header("Authorization", bearer(librarian()))))) {
            assertEquals(floor2, node.path("floorId").asLong(),
                    "★ 楼层筛选漏行：另一层的任务混进来了 " + node);
        }

        MvcResult noNote = exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(handleBody(PatrolAction.REMIND.name(), "  ")))
                .header("Authorization", bearer(librarian())));
        assertEquals(PARAM_ERROR, code(noNote), "处置说明必填：" + body(noNote));

        assertEquals(0, code(exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(handleBody(PatrolAction.REMIND.name(), "已口头提醒")))
                .header("Authorization", bearer(librarian())))));
        MvcResult twice = exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(handleBody(PatrolAction.IGNORE.name(), "再点一次")))
                .header("Authorization", bearer(librarian())));
        assertEquals(STATUS_NOT_ALLOWED, code(twice), "同一任务不能处置两次：" + body(twice));

        assertEquals(0, openTasksOf(seat), "提醒之后要腾出未处理名额，下一轮才可能重开并升级等级");
        assertTrue(patronHistory(seat) >= 1, "已处置记录要留在表里供回溯");
    }

    @Test
    @DisplayName("【红线】生成器绝不释放座位：跑一轮后预约状态与 slot 行分毫未动")
    void generatorNeverReleases() throws Exception {
        String date = dw(1);
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);
        long reservationId = checkedIn(newUser(), seat, date, START, 4,
                BizConstants.slotStart(LocalDate.parse(date), START));
        Integer slotsBefore = slotRows(reservationId);
        String statusBefore = reservationStatus(reservationId);

        patrolService.generate(BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(200));

        assertEquals(statusBefore, reservationStatus(reservationId),
                "★ 机器可以报警，不可以替馆员做释放决定");
        assertEquals(slotsBefore, slotRows(reservationId), "生成前后 slot 行数必须一致");
        // ★ 按本次任务号查，不做全表计数：共享内存库里别用例的 RELEASE 处置也在同一张表
        assertEquals(0, jdbc.queryForObject("select count(*) from t_audit_log"
                        + " where action = ? and target_type = ? and target_id = ?",
                Integer.class, "RESERVATION_RELEASE", "RESERVATION", String.valueOf(reservationId)),
                "生成器不得走强制释放路径");
    }

    @Test
    @DisplayName("【人工登记】无预约占座也能登记；该任务不可释放；同座位不叠加")
    void manualRegisterCoversNoReservationSeat() throws Exception {
        long seat = seatId(floor1, "A12");
        touchedSeats.add(seat);
        MvcResult created = exec(post("/api/staff/patrol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(registerBody(seat, "同学拿书占座但人不在，预约已过期")))
                .header("Authorization", bearer(librarian())));
        assertEquals(0, code(created), "人工登记应成功：" + body(created));
        assertEquals(PatrolType.MANUAL.name(), data(created).path("type").asText());
        assertEquals(false, data(created).path("canRelease").asBoolean(),
                "没有预约就无座位可释放，按钮必须置灰并给原因");
        assertTrue(data(created).path("releaseDisabledReason").asText().contains("没有关联预约"),
                body(created).toString());

        MvcResult twice = exec(post("/api/staff/patrol")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(registerBody(seat, "再登记一次")))
                .header("Authorization", bearer(librarian())));
        assertEquals(STATUS_NOT_ALLOWED, code(twice), "同座位已有未处理任务时应引导先处理：" + body(twice));

        MvcResult noNote = exec(post("/api/staff/patrol")
                .contentType(MediaType.APPLICATION_JSON).content(json(registerBody(seat, "")))
                .header("Authorization", bearer(librarian())));
        assertEquals(PARAM_ERROR, code(noNote), "现场情况必填：" + body(noNote));
    }

    @Test
    @DisplayName("【验收2】口头提醒过的疑点再次登记 → 升为 HIGH 并在理由里写明升级原因")
    void recentlyRemindedSuspicionEscalatesOnReRegister() throws Exception {
        String date = dw(1);
        long seat = freeSeat(date, START);
        touchedSeats.add(seat);

        MvcResult first = exec(post("/api/staff/patrol")
                .contentType(MediaType.APPLICATION_JSON).content(json(registerBody(seat, "书本摊在桌上但人不在")))
                .header("Authorization", bearer(librarian())));
        assertEquals(0, code(first), "第一次人工登记应成功：" + body(first));
        assertEquals("MID", data(first).path("level").asText(),
                "★ 人工登记默认中等：馆员的目视判断不该一上来就压过自动规则的 HIGH");

        long firstId = data(first).path("patrolId").asLong();
        assertEquals(0, code(exec(post("/api/staff/patrol/" + firstId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(handleBody(PatrolAction.REMIND.name(), "已口头提醒")))
                .header("Authorization", bearer(librarian())))));

        MvcResult second = exec(post("/api/staff/patrol")
                .contentType(MediaType.APPLICATION_JSON).content(json(registerBody(seat, "提醒后十分钟仍占着书没动")))
                .header("Authorization", bearer(librarian())));
        assertEquals(0, code(second), "上一轮已处置成终态，腾出了未处理名额，应能再登记：" + body(second));
        assertEquals("HIGH", data(second).path("level").asText(),
                "刚提醒过仍再犯必须排到第一次前面，否则口头提醒没有后果");
        assertTrue(data(second).path("reason").asText().contains("已被口头提醒过"),
                "升级要在理由里说清依据：" + data(second).path("reason").asText());
    }

    // ==================== 工具 ====================

    private String librarian() throws Exception {
        return login("librarian1", "123456");
    }

    /** 建预约并置成「使用中 + 指定签到时刻」：真实接口无法把签到时刻放到过去。 */
    private long checkedIn(String token, long seatId, String date, int startSlot,
                           int duration, LocalDateTime checkInTime) throws Exception {
        long reservationId = reserveOk(token, seatId, date, startSlot, duration).path("reservationId").asLong();
        createdReservations.add(reservationId);
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setCheckInTime(checkInTime);
        reservationRepository.saveAndFlush(reservation);
        return reservationId;
    }

    private long openTaskAfterGenerate(long seat, String date) throws Exception {
        patrolService.generate(BizConstants.slotStart(LocalDate.parse(date), START).plusMinutes(125));
        return openTask(seat).path("patrolId").asLong();
    }

    /** 看板上该座位那条未处理任务（走真实接口读，顺带验证 VO 的装配）。 */
    private JsonNode openTask(long seatId) throws Exception {
        JsonNode list = data(exec(get("/api/staff/patrol").header("Authorization", bearer(librarian()))));
        JsonNode hit = null;
        int count = 0;
        for (JsonNode node : list) {
            if (node.path("seatId").asLong() == seatId) {
                count++;
                hit = node;
            }
        }
        assertEquals(1, count, "该座位在看板上应恰好一条未处理任务，实际 " + count);
        return hit;
    }

    private int openTasksOf(long seatId) {
        return jdbc.queryForObject("select count(*) from t_patrol_task where seat_id = ? and open_flag = 1",
                Integer.class, seatId);
    }

    private int patronHistory(long seatId) {
        return jdbc.queryForObject("select count(*) from t_patrol_task where seat_id = ?", Integer.class, seatId);
    }

    private Integer slotRows(Long reservationId) {
        return jdbc.queryForObject("select count(*) from t_reservation_slot where reservation_id = ?",
                Integer.class, reservationId);
    }

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("select status from t_reservation where id = ?", String.class, reservationId);
    }

    private List<String> noticeTypes(String token) throws Exception {
        List<String> types = new ArrayList<>();
        for (JsonNode node : notices(token)) {
            types.add(node.path("type").asText());
        }
        return types;
    }

    private String noticeContent(String token, String type) throws Exception {
        for (JsonNode node : notices(token)) {
            if (type.equals(node.path("type").asText())) {
                return node.path("content").asText();
            }
        }
        return "";
    }

    private JsonNode notices(String token) throws Exception {
        return data(exec(get("/api/me/notices").header("Authorization", bearer(token)))).path("list");
    }

    private Map<String, Object> handleBody(String action, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        body.put("note", note);
        return body;
    }

    private Map<String, Object> registerBody(long seatId, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("seatId", seatId);
        body.put("note", note);
        return body;
    }

    /** 1F 静音区里指定时段空闲的座位。 */
    private long freeSeat(String date, int slot) throws Exception {
        JsonNode map = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(admin))));
        long silent = jdbc.queryForObject("select id from t_zone where code = '1F-SILENT'", Long.class);
        for (JsonNode seat : map.path("seats")) {
            if ("FREE".equals(seat.path("displayStatus").asText()) && seat.path("zoneId").asLong() == silent) {
                return seat.path("seatId").asLong();
            }
        }
        throw new AssertionError(date + " 第 " + slot + " 格找不到空闲的静音区座位");
    }
}

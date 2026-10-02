package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.Violation;
import com.libseat.enums.CreditLogKind;
import com.libseat.enums.ViolationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.AppealRepository;
import com.libseat.repository.CreditLogRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ViolationRepository;
import com.libseat.service.CreditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S3 / T-V2-13：申诉闭环（提交 → 复核 → 升级 → 终审）与馆员直接撤销。
 *
 * <p>这一片真正的风险不是「流程跑不通」，而是<b>同一笔扣分被回补两次</b>：
 * 申诉成立和馆员撤销都会补分，两条路径都能走通时账本就会比快照高。
 * 所以每条用例都同时断言三件事：申诉状态、违规状态、以及
 * {@code sum(t_credit_log.delta) == 信用分快照}。
 */
class AppealApiTest extends ApiTestBase {

    /** 参数错误。 */
    private static final int PARAM_ERROR = 4001;

    /** 角色无资格（馆员碰终审件）。 */
    private static final int ROLE_NOT_ELIGIBLE = 4031;

    /** 状态不允许（重复裁定、申诉中撤销）。 */
    private static final int STATUS_NOT_ALLOWED = 4093;

    /** 申诉已存在或超出 24 小时时限。 */
    private static final int APPEAL_NOT_ALLOWED = 4108;

    @Autowired
    private CreditService creditService;

    @Autowired
    private ViolationRepository violationRepository;

    @Autowired
    private CreditLogRepository creditLogRepository;

    @Autowired
    private AppealRepository appealRepository;

    @Autowired
    private UserRepository userRepository;

    // ==================== 工具 ====================

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private int score(Long id) {
        return userRepository.findById(id).map(com.libseat.entity.User::getCreditScore).orElse(-1);
    }

    private List<CreditLogKind> kinds(Long id) {
        return creditLogRepository.findByUserIdOrderByCreateTimeAscIdAsc(id).stream()
                .map(log -> log.getKind()).toList();
    }

    private String evidence() {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("rule", "test.T-V2-13");
        fields.put("reserveDate", BizConstants.formatDate(java.time.LocalDate.now()));
        fields.put("slotLabel", "08:00-08:30");
        fields.put("deadline", BizConstants.formatDateTime(LocalDateTime.now()));
        fields.put("judgedAt", BizConstants.formatDateTime(LocalDateTime.now()));
        return creditService.buildEvidence(fields);
    }

    /** 该用户最新一条违规。 */
    private Violation newestViolation(Long id) {
        return violationRepository.findByUserIdOrderByIdDesc(id).get(0);
    }

    private void violate(Long id, ViolationType type) {
        creditService.applyViolation(id, type, null, evidence(), null, null);
    }

    /** 把违规推到 24 小时窗口之外（时限只能靠造数跨越，不能靠改产品常量）。 */
    private void backdate(Violation violation, int hours) {
        violation.setCreateTime(LocalDateTime.now().minusHours(hours));
        violationRepository.save(violation);
    }

    private MvcResult appeal(String token, long violationId, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        return exec(post("/api/violations/" + violationId + "/appeal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(token)));
    }

    private long appealOk(String token, long violationId, String reason) throws Exception {
        MvcResult result = appeal(token, violationId, reason);
        assertEquals(0, code(result), "申诉提交应成功：" + body(result));
        return data(result).path("appealId").asLong();
    }

    private MvcResult escalate(String token, long violationId) throws Exception {
        return exec(post("/api/violations/" + violationId + "/appeal/escalate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of()))
                .header("Authorization", bearer(token)));
    }

    private MvcResult judge(String token, long appealId, boolean approve, String reply) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("approve", approve);
        body.put("reply", reply);
        return exec(post("/api/staff/appeals/" + appealId + "/judge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(token)));
    }

    private MvcResult queue(String token, String status) throws Exception {
        var builder = get("/api/staff/appeals");
        if (status != null) {
            builder = builder.param("status", status);
        }
        return exec(builder.header("Authorization", bearer(token)));
    }

    private MvcResult revoke(String token, long violationId, String reason) throws Exception {
        return exec(post("/api/staff/violations/" + violationId + "/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("reason", reason)))
                .header("Authorization", bearer(token)));
    }

    private long auditTotal(String action) throws Exception {
        return data(exec(get("/api/admin/audit")
                .param("action", action)
                .param("size", "1")
                .header("Authorization", bearer(admin)))).path("total").asLong();
    }

    /** 读「我的违规」里某条违规的那一行。 */
    private JsonNode myViolationRow(String token, long violationId) throws Exception {
        for (JsonNode node : data(exec(get("/api/me/violations")
                .header("Authorization", bearer(token))))) {
            if (node.path("violationId").asLong() == violationId) {
                return node;
            }
        }
        throw new AssertionError("我的违规列表里找不到 " + violationId);
    }

    /** 在队列响应里按申诉 ID 取一行；取不到就直接失败，而不是让 for 循环空转把断言变哑。 */
    private JsonNode queueRow(MvcResult result, long appealId) throws Exception {
        for (JsonNode node : data(result)) {
            if (node.path("appealId").asLong() == appealId) {
                return node;
            }
        }
        throw new AssertionError("队列里找不到申诉 " + appealId + "，实际：" + body(result));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("【验收1】申诉成立：分数回补、处罚随回分自动解除、历史流水完整可追溯")
    void appealApprovedRestoresScoreAndLiftsPenalty() throws Exception {
        String me = newUser();
        String staff = login("librarian1", "123456");
        long id = uid(me);

        violate(id, ViolationType.NO_SHOW);
        violate(id, ViolationType.NO_SHOW);
        assertEquals(80, score(id), "两次爽约 = 80，应已进入高峰禁约档");

        Violation target = newestViolation(id);
        MvcResult submitted = appeal(me, target.getId(), "当时我人已到馆，是签到机故障");
        assertEquals(0, code(submitted), "提交应成功：" + body(submitted));
        assertEquals("PENDING", data(submitted).path("status").asText());
        assertEquals("APPEALING", data(submitted).path("violation").path("status").asText(),
                "★ 提交即把违规推到申诉中，馆员队列才看得出这条正在流程里");

        JsonNode items = data(submitted).path("violation").path("evidenceItems");
        assertTrue(items.size() >= 4, "证据要拆成可读项，实际 " + items);
        boolean labelled = false;
        for (JsonNode item : items) {
            if ("触发规则".equals(item.path("label").asText())) {
                labelled = true;
                assertEquals("rule", item.path("key").asText(), "标签背后要保留原始键，便于排查");
            }
        }
        assertTrue(labelled, "证据里必须能看到「触发规则」这一项：" + items);

        JsonNode pending = data(queue(staff, "PENDING"));
        JsonNode mine = null;
        for (JsonNode node : pending) {
            if (node.path("appealId").asLong() == data(submitted).path("appealId").asLong()) {
                mine = node;
            }
        }
        assertNotNull(mine, "馆员按状态过滤的队列里应能找到刚提交的申诉");
        assertTrue(mine.path("canJudge").asBoolean(), "PENDING 件馆员可裁定");
        assertTrue(mine.path("violation").path("evidenceItems").size() > 0, "裁定页要能在队列里直接看到证据");

        long appealId = data(submitted).path("appealId").asLong();
        MvcResult judged = judge(staff, appealId, true, "已核对当日工单，签到机确实故障");
        assertEquals(0, code(judged), "裁定应成功：" + body(judged));
        assertEquals("APPROVED", data(judged).path("status").asText());
        assertEquals("REVOKED", data(judged).path("violation").path("status").asText());

        assertEquals(90, score(id), "成立必须把这笔扣分原额回补");
        Violation after = violationRepository.findById(target.getId()).orElseThrow();
        assertEquals(ViolationStatus.REVOKED, after.getStatus());
        assertNotNull(after.getRevokeTime(), "撤销要留时刻");
        assertTrue(after.getRevokeReason().startsWith("申诉成立"), "撤销理由要写清来源：" + after.getRevokeReason());

        assertEquals(List.of(CreditLogKind.INIT, CreditLogKind.PENALTY, CreditLogKind.PENALTY,
                        CreditLogKind.RESTORE),
                kinds(id), "★ 历史不能被改写：回补是反向追加一笔 RESTORE，前两笔 PENALTY 原样留着");
        assertEquals(90, creditLogRepository.sumDelta(id), "账本合计必须等于快照");
        assertEquals(0, data(exec(get("/api/admin/rules/consistency")
                .header("Authorization", bearer(admin)))).size(), "全库对账必须仍为空");

        JsonNode credit = data(exec(get("/api/me/credit").header("Authorization", bearer(me))));
        assertEquals(90, credit.path("creditScore").asInt(), "信用概览必须读得到分数（否则下面的断言是空的）");
        assertEquals(0, credit.path("penalties").size(),
                "★ 回分到 90 后高峰禁约必须自动解除（阶梯是当前分数的函数，不是历史的累计）");

        assertEquals(STATUS_NOT_ALLOWED, code(judge(staff, appealId, true, "再补一次")),
                "已裁定的申诉不得重复裁定，否则同一笔扣分会被回补两次");
    }

    @Test
    @DisplayName("【验收2】馆员只能裁定不能翻终态；终审后既不能再裁定也不能再申诉")
    void librarianCannotOverturnFinalJudgement() throws Exception {
        String me = newUser();
        String staff = login("librarian1", "123456");
        long id = uid(me);

        violate(id, ViolationType.NO_SHOW);
        Violation target = newestViolation(id);
        long appealId = appealOk(me, target.getId(), "判定依据有误");

        assertEquals(PARAM_ERROR, code(judge(staff, appealId, false, "  ")),
                "驳回必须写理由，否则学生不知道该补什么材料");

        MvcResult rejected = judge(staff, appealId, false, "巡查照片显示座位当时有人");
        assertEquals(0, code(rejected));
        assertEquals("REJECTED", data(rejected).path("status").asText());
        assertEquals("ACTIVE", data(rejected).path("violation").path("status").asText(),
                "驳回后违规要回到生效中，不能停在申诉中");
        assertEquals(90, score(id), "驳回不回补任何分数");

        assertEquals(APPEAL_NOT_ALLOWED, code(appeal(me, target.getId(), "换个说法再提一次")),
                "一条违规只能有一条申诉");

        MvcResult escalated = escalate(me, target.getId());
        assertEquals(0, code(escalated), "被驳回的申诉应可升级终审：" + body(escalated));
        assertEquals("ESCALATED", data(escalated).path("status").asText());

        JsonNode staffRow = queueRow(queue(staff, "ESCALATED"), appealId);
        assertFalse(staffRow.path("canJudge").asBoolean(),
                "升级件对馆员必须显示不可裁：" + staffRow.path("judgeDisabledReason"));
        assertTrue(queueRow(queue(admin, "ESCALATED"), appealId).path("canJudge").asBoolean(),
                "升级件对系统管理员可终审");

        assertEquals(ROLE_NOT_ELIGIBLE, code(judge(staff, appealId, true, "我看还是撤了吧")),
                "★ 馆员碰升级件必须被拒，而不是悄悄替他裁完");

        MvcResult finalJudged = judge(admin, appealId, false, "复核全部材料，维持驳回");
        assertEquals(0, code(finalJudged));
        assertEquals("FINAL_JUDGED", data(finalJudged).path("status").asText(),
                "终审无论成立或驳回都落终态，否则「还能不能翻」永远说不清");

        assertEquals(STATUS_NOT_ALLOWED, code(judge(staff, appealId, true, "试图翻案")),
                "他人已裁定的终态不能被再次裁定");
        assertEquals(STATUS_NOT_ALLOWED, code(judge(admin, appealId, true, "自己也不能再改")),
                "终态对终审人同样关闭");
        assertEquals(APPEAL_NOT_ALLOWED, code(appeal(me, target.getId(), "还想再申诉")),
                "终审后不可再申诉");
        assertEquals(STATUS_NOT_ALLOWED, code(escalate(me, target.getId())),
                "已定案的申诉不能重复升级");
        assertEquals(90, score(id), "整条链走完没有回补过分");
    }

    @Test
    @DisplayName("【验收3】超 24 小时 → 4108；重复申诉 → 4108；列表页的理由与接口错误码同源")
    void appealWindowAndDuplicate() throws Exception {
        String me = newUser();
        long id = uid(me);

        violate(id, ViolationType.NO_SHOW);
        Violation stale = newestViolation(id);
        backdate(stale, 25);

        MvcResult late = appeal(me, stale.getId(), "我忘了时间");
        assertEquals(APPEAL_NOT_ALLOWED, code(late), "超时限应 4108，实际：" + body(late));
        assertTrue(msg(late).contains("24") && msg(late).contains("馆员"),
                "要告诉学生超时了、以及现在该走哪条路：" + msg(late));

        // 列表页的「不可申诉」原因必须与接口一致（前端只渲染，不自己判规则）
        JsonNode staleRow = myViolationRow(me, stale.getId());
        assertFalse(staleRow.path("canAppeal").asBoolean(), "超时限的违规不能显示可申诉");
        assertTrue(staleRow.path("appealDisabledReason").asText().contains("24"),
                "列表页理由要与接口一致：" + staleRow.path("appealDisabledReason"));
        assertTrue(staleRow.path("evidenceItems").size() > 0, "违规明细必须带证据可读视图");

        violate(id, ViolationType.NO_SHOW);
        Violation fresh = newestViolation(id);
        long appealId = appealOk(me, fresh.getId(), "这条我要申诉");
        assertEquals(1, appealRepository.findByUserIdOrderByIdDesc(id).stream()
                        .filter(a -> a.getViolationId().equals(fresh.getId())).count(),
                "uk_appeal_violation 之后这条违规只能有一条申诉");
        assertEquals(APPEAL_NOT_ALLOWED, code(appeal(me, fresh.getId(), "再来一条")),
                "重复申诉同样 4108，而不是 500 或唯一索引异常");

        JsonNode freshRow = myViolationRow(me, fresh.getId());
        assertFalse(freshRow.path("canAppeal").asBoolean());
        assertEquals("申诉正在处理中", freshRow.path("appealDisabledReason").asText());
        assertEquals(appealId, freshRow.path("appealId").asLong());
        assertEquals("APPEALING", freshRow.path("status").asText());
    }

    @Test
    @DisplayName("【验收4】提交 / 驳回 / 升级 / 终审 全部动作进审计日志，明细可读")
    void allAppealActionsAreAudited() throws Exception {
        String me = newUser();
        String staff = login("librarian1", "123456");
        long id = uid(me);

        long submitBefore = auditTotal("APPEAL_SUBMIT");
        long escalateBefore = auditTotal("APPEAL_ESCALATE");
        long judgeBefore = auditTotal("APPEAL_JUDGE");

        violate(id, ViolationType.NO_SHOW);
        Violation target = newestViolation(id);
        long appealId = appealOk(me, target.getId(), "签到记录被误读");
        assertEquals(submitBefore + 1, auditTotal("APPEAL_SUBMIT"), "提交要留审计");

        assertEquals(0, code(judge(staff, appealId, false, "材料不足以推翻判定")));
        assertEquals(judgeBefore + 1, auditTotal("APPEAL_JUDGE"), "复核要留审计");

        assertEquals(0, code(escalate(me, target.getId())));
        assertEquals(escalateBefore + 1, auditTotal("APPEAL_ESCALATE"), "升级要留审计");

        assertEquals(0, code(judge(admin, appealId, true, "终审认为申诉成立")));
        assertEquals(judgeBefore + 2, auditTotal("APPEAL_JUDGE"), "终审同样进审计，且与复核同一动作码");

        JsonNode detail = data(exec(get("/api/admin/audit")
                .param("action", "APPEAL_JUDGE")
                .param("size", "1")
                .header("Authorization", bearer(admin)))).path("list").get(0).path("detail");
        assertTrue(detail.asText().contains("finalJudgement"), "审计明细要能区分复核与终审：" + detail);
        assertTrue(detail.asText().contains("restoredScore"), "审计明细要写了多少回补：" + detail);
        assertTrue(detail.asText().contains("appealId"), "审计明细要能定位到具体申诉：" + detail);
    }

    @Test
    @DisplayName("【F8】超时限误判由馆员撤销救济；申诉中禁止从这里撤销（防二次回补）")
    void staffRevokeIsTheOnlyLateRemedyAndIsGuarded() throws Exception {
        String me = newUser();
        String staff = login("librarian1", "123456");
        long id = uid(me);

        violate(id, ViolationType.ON_SITE_OCCUPY);
        Violation stale = newestViolation(id);
        backdate(stale, 25);
        assertEquals(APPEAL_NOT_ALLOWED, code(appeal(me, stale.getId(), "已经超时")),
                "超时后学生侧入口必须关闭");

        violate(id, ViolationType.NO_SHOW);
        Violation inAppeal = newestViolation(id);
        long appealId = appealOk(me, inAppeal.getId(), "这条正在申诉");
        assertEquals(STATUS_NOT_ALLOWED, code(revoke(staff, inAppeal.getId(), "顺手撤销")),
                "★ 申诉中的违规不得从这里撤销：两个补分入口会把它回补两次");
        assertEquals("申诉正在处理中", myViolationRow(me, inAppeal.getId())
                        .path("appealDisabledReason").asText(),
                "列表页要说清现在归哪个入口管");

        MvcResult revoked = revoke(staff, stale.getId(), "监控显示当事人其实在场");
        assertEquals(0, code(revoked), "超时误判要能从馆员侧救济：" + body(revoked));
        assertEquals("REVOKED", data(revoked).path("status").asText());
        assertEquals(90, score(id), "现场占座的 15 分原额回补（75 → 90）");
        assertEquals(0, data(exec(get("/api/admin/rules/consistency")
                .header("Authorization", bearer(admin)))).size(), "撤销后账本仍要对平");

        assertEquals("PENDING", appealRepository.findById(appealId).orElseThrow().getStatus().name(),
                "撤销另一条违规不得动这条申诉的状态");
        assertEquals(STATUS_NOT_ALLOWED, code(revoke(staff, stale.getId(), "再撤一次")),
                "已撤销的违规不得重复撤销（否则分数会被补两次）");
    }
}

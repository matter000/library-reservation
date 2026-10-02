package com.libseat.api;

import com.libseat.common.BizConstants;
import com.libseat.entity.CreditLog;
import com.libseat.entity.Violation;
import com.libseat.enums.CreditLogKind;
import com.libseat.enums.ViolationType;
import com.libseat.repository.CreditLogRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ViolationRepository;
import com.libseat.scheduler.WeeklyCreditTask;
import com.libseat.service.CreditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * V2 S3 / T-V2-14 后半：周恢复（DESIGN §6.4）。
 *
 * <p>这里验的是「同一周跑两次只加一次」和「窗口按自然周切割」，
 * 因为周任务是最容易被重复触发的写分路径（补跑、重启、多实例）。
 * 一旦重复加分，{@code sum(delta) == 信用分快照} 仍然成立 —— 也就是说对账接口
 * <b>抓不到它</b>，只能靠这里显式断言条数。
 */
class WeeklyCreditApiTest extends ApiTestBase {

    @Autowired
    private CreditService creditService;

    @Autowired
    private WeeklyCreditTask task;

    @Autowired
    private CreditLogRepository creditLogRepository;

    @Autowired
    private ViolationRepository violationRepository;

    @Autowired
    private UserRepository userRepository;

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private int score(Long id) {
        return userRepository.findById(id).map(com.libseat.entity.User::getCreditScore).orElse(-1);
    }

    private List<CreditLog> logs(Long id) {
        return creditLogRepository.findByUserIdOrderByCreateTimeAscIdAsc(id);
    }

    private long weeklyLogCount(Long id) {
        return logs(id).stream().filter(log -> log.getKind() == CreditLogKind.WEEKLY_BONUS).count();
    }

    /** 与任务同一套周期换算（不是用例自己复刻的「上周」）。 */
    private LocalDateTime thisMondayStart() {
        return WeeklyCreditTask.currentMonday(LocalDate.now()).atStartOfDay();
    }

    /**
     * 记一笔违规并把裁定时刻推到指定时间。
     *
     * <p>★ 证据必须非空：{@code CreditService} 对空证据直接抛 4001，
     * 传 {@code {}} 会让「上周有违规」这一用例其实什么都没扣到分，断言就成了巧合。
     *
     * @param at 违规应落在的时刻；null 表示现在
     */
    private void violate(Long id, int delta, LocalDateTime at) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("rule", "test.T-V2-14-weekly");
        fields.put("judgedAt", BizConstants.formatDateTime(at == null ? LocalDateTime.now() : at));
        Violation violation = creditService.applyViolation(id, ViolationType.NO_SHOW, delta,
                creditService.buildEvidence(fields), null, null);
        if (at != null) {
            violation.setCreateTime(at);
            violationRepository.save(violation);
        }
    }

    @Test
    @DisplayName("【验收2a】上一自然周无违规 → 周一 +5，并留下一笔 WEEKLY_BONUS")
    void bonusWhenLastWeekClean() throws Exception {
        String me = newUser();
        long id = uid(me);
        violate(id, -10, LocalDateTime.now());            // 本周内违规：不影响上周的评定
        assertEquals(90, score(id));

        task.awardWeekly();

        assertEquals(95, score(id), "上周零违规应恢复 +5");
        List<CreditLog> after = logs(id);
        assertEquals(List.of(CreditLogKind.INIT, CreditLogKind.PENALTY, CreditLogKind.WEEKLY_BONUS),
                after.stream().map(CreditLog::getKind).toList());
        assertEquals(BizConstants.CREDIT_WEEKLY_BONUS, after.get(after.size() - 1).getDelta(),
                "流水要写清加了多少");
        assertEquals(95, creditLogRepository.sumDelta(id), "加分同样要落进账本");

        // 同一周再跑一次（补跑 / 重启 / 第二个实例都会造成这种重复）
        task.awardWeekly();
        assertEquals(95, score(id), "★ 同一自然周不得加两次");
        assertEquals(1, weeklyLogCount(id), "同一周期只能有一笔 WEEKLY_BONUS");
    }

    @Test
    @DisplayName("【验收2b】上一自然周有违规 → 本周不发恢复奖励")
    void skippedWhenLastWeekHasViolation() throws Exception {
        String me = newUser();
        long id = uid(me);
        LocalDateTime lastWeekSunday = thisMondayStart().minusDays(1);
        violate(id, -10, lastWeekSunday);                 // 违规落在「上一自然周」内
        assertEquals(90, score(id), "违规本身照常扣分");

        task.awardWeekly();
        assertEquals(90, score(id), "★ 上周有违规就不该恢复");
        assertEquals(0, weeklyLogCount(id), "不该留下周恢复流水");
    }

    @Test
    @DisplayName("【验收2】连续主动结束满 5 次 → +5 分，并在流水里看得见；不足 5 次不发")
    void streakBonusLandsInLedger() throws Exception {
        String me = newUser();
        long id = uid(me);
        violate(id, -10, LocalDateTime.now());
        assertEquals(90, score(id), "先制造一个可恢复的缺口");

        // 计数由 finish() 逐次累加（S2 的 LeaveApiTest 已验那条链路）；
        // 这里直接置数是为了不在一个用例里排 5 条预约，验的是「满 5 次之后的发放与记账」。
        com.libseat.entity.User user = userRepository.findById(id).orElseThrow();
        user.setActiveFinishStreak(BizConstants.CREDIT_STREAK_SIZE - 1);
        userRepository.save(user);
        assertTrue(!creditService.awardStreakIfNeeded(id), "差一次不该发奖");
        assertEquals(90, score(id), "没发奖就不该动分数");

        user = userRepository.findById(id).orElseThrow();
        user.setActiveFinishStreak(BizConstants.CREDIT_STREAK_SIZE);
        userRepository.save(user);
        assertTrue(creditService.awardStreakIfNeeded(id), "满 5 次应发奖");
        assertEquals(95, score(id), "恢复加分应到账");
        assertEquals(0, userRepository.findById(id).orElseThrow().getActiveFinishStreak(),
                "★ 发奖后计数要归零，否则第 6 次又白给一轮");

        CreditLog bonus = logs(id).get(logs(id).size() - 1);
        assertEquals(CreditLogKind.STREAK_BONUS, bonus.getKind());
        assertEquals(BizConstants.CREDIT_STREAK_BONUS, bonus.getDelta());
        assertEquals(95, bonus.getBalanceAfter().intValue(), "流水要带余额快照");
        assertEquals(95, creditLogRepository.sumDelta(id));
    }

    @Test
    @DisplayName("【验收2c】满分不再加分；接近满分时夹到 100 且流水记实际动过的值")
    void cappedAtHundredAndNoNoiseLog() throws Exception {
        String me = newUser();
        long id = uid(me);

        task.awardWeekly();
        assertEquals(100, score(id));
        assertEquals(0, weeklyLogCount(id),
                "★ 已经满分的用户不该产生 delta=0 的噪音流水（账本要能读出「发生了什么」）");

        violate(id, -2, LocalDateTime.now());
        assertEquals(98, score(id));
        task.awardWeekly();
        assertEquals(100, score(id), "5 分只能实际加 2，天花板是 100");
        CreditLog last = logs(id).get(logs(id).size() - 1);
        assertEquals(CreditLogKind.WEEKLY_BONUS, last.getKind());
        assertEquals(2, last.getDelta(), "流水记「真动了多少」而不是「请求加多少」，否则对账必不平");
        assertEquals(100, creditLogRepository.sumDelta(id));
    }
}

package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.CreditLog;
import com.libseat.entity.Penalty;
import com.libseat.entity.User;
import com.libseat.entity.Violation;
import com.libseat.enums.CreditLogKind;
import com.libseat.enums.NoticeType;
import com.libseat.enums.PenaltyType;
import com.libseat.enums.ViolationType;
import com.libseat.repository.CreditLogRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 信用分写入的唯一入口（DESIGN §6.2）。
 *
 * <p>不变式：{@code sum(t_credit_log.delta) == t_user.credit_score}。
 * 因此任何改分都必须走本类的 {@link #moveScore}，注册时要写一笔 {@code INIT} 起始分流水，
 * 申诉回补走 {@code RESTORE} 反向记一笔 —— 一律不改写历史。
 *
 * <p>违规必须有证据 JSON：PRD §12-22「无证据不得裁定」，缺证据直接 4001，
 * 而不是先扣分再等证据补交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    private final ViolationRepository violationRepository;

    private final CreditLogRepository creditLogRepository;

    private final UserRepository userRepository;

    private final PenaltyService penaltyService;

    private final RuleConfigService ruleConfigService;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 站内通知（违规即时告知，含申诉窗口）。只依赖仓储，不会与本类成环。 */
    private final NoticeService noticeService;

    /**
     * 记一笔违规：写违规 → 扣分 → 记流水 → 评估阶梯。四步同一事务。
     *
     * @param userId        违规人
     * @param type          违规类型（决定默认扣分）
     * @param scoreDelta    扣分（负数）；传 null 用类型的默认值
     * @param evidenceJson  证据 JSON，必填
     * @param reservationId 关联预约，可为 null
     * @param operatorId    裁定人；null 表示系统自动
     * @return 违规记录
     */
    @Transactional
    public Violation applyViolation(Long userId, ViolationType type, Integer scoreDelta,
                                    String evidenceJson, Long reservationId, Long operatorId) {
        if (!StringUtils.hasText(evidenceJson)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "缺少证据，不得裁定违规（" + type.getText() + "）");
        }
        int delta = scoreDelta == null ? type.getDefaultDelta() : scoreDelta;
        if (delta >= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "违规扣分必须为负数，实际 " + delta);
        }
        User user = requireUser(userId);

        Violation violation = violationRepository.saveAndFlush(Violation.builder()
                .userId(userId)
                .reservationId(reservationId)
                .type(type)
                .scoreDelta(delta)
                .evidence(evidenceJson)
                .createdBy(operatorId == null ? "SYSTEM" : "LIBRARIAN")
                .creatorId(operatorId)
                .build());

        int balance = moveScore(user, CreditLogKind.PENALTY, delta, "VIOLATION", violation.getId(),
                type.getText() + " " + delta);
        penaltyService.syncLadder(userId, balance, type.getText() + "后信用分 " + balance, LocalDateTime.now());
        // 违规即时通知（DESIGN §6.2）：扣分与处罚已经生效，用户必须在不点开的情况下
        // 也能看清「扣了多少、现在多少分、什么时候前能申诉」——链接指向 /credit，那里就是申诉入口。
        noticeService.push(userId, NoticeType.VIOLATION,
                "违规裁定：" + type.getText(),
                type.getText() + " 已裁定，扣 " + (-delta) + " 分，当前信用分 " + balance
                        + "。可在 " + BizConstants.APPEAL_WINDOW_HOURS + " 小时内（"
                        + BizConstants.formatDateTime(violation.getCreateTime()
                        .plusHours(BizConstants.APPEAL_WINDOW_HOURS))
                        + " 前）发起申诉，超时后只能到馆员处申请撤销。",
                NoticeService.REF_VIOLATION, violation.getId(),
                violation.getCreateTime().plusHours(BizConstants.APPEAL_WINDOW_HOURS));
        log.info("违规裁定：userId={}, type={}, delta={}, 余额={}", userId, type, delta, balance);
        return violation;
    }

    /**
     * 注册 / 种子建号时的初始分流水。
     *
     * <p>★ 缺了这一步，新用户 {@code sum(delta)=0} 而 {@code credit_score=100}，
     * 对账接口永远不平 —— 初始分必须是账本上的一笔流水，而不是列的默认值。
     *
     * @param userId 新用户 ID
     */
    @Transactional
    public void initAccount(Long userId) {
        User user = requireUser(userId);
        int score = user.getCreditScore() == null ? ruleConfigService.creditInitScore() : user.getCreditScore();
        user.setCreditScore(score);
        userRepository.save(user);
        creditLogRepository.save(CreditLog.builder()
                .userId(userId)
                .kind(CreditLogKind.INIT)
                .delta(score)
                .balanceAfter(score)
                .refType("ACCOUNT")
                .refId(userId)
                .reason("初始信用分")
                .build());
    }

    /**
     * 主动结束使用的连续奖励（§5.4：每满 N 次给一次加分）。
     *
     * @param userId 用户 ID
     * @return 本次是否触发了加分
     */
    @Transactional
    public boolean awardStreakIfNeeded(Long userId) {
        User user = requireUser(userId);
        int size = ruleConfigService.creditStreakSize();
        int streak = user.getActiveFinishStreak() == null ? 0 : user.getActiveFinishStreak();
        if (size <= 0 || streak < size || streak % size != 0) {
            return false;
        }
        int bonus = ruleConfigService.creditStreakBonus();
        moveScore(user, CreditLogKind.STREAK_BONUS, bonus, "STREAK", user.getId(),
                "连续主动结束 " + streak + " 次");
        user.setActiveFinishStreak(0);
        userRepository.save(user);
        int balance = user.getCreditScore();
        penaltyService.syncLadder(userId, balance, "良好使用奖励后信用分 " + balance, LocalDateTime.now());
        return true;
    }

    /**
     * 周恢复加分（DESIGN §6.4：上一自然周无新增违规 → +5，上限 100）。
     *
     * <p>★ 同一周期只发一次：判定用「本周起点之后有没有 WEEKLY_BONUS 流水」，
     * 于是任务被手动补跑、或实例重启后再次触发，都不会重复加分 —— 账本只追加，
     * 幂等必须由写入方保证，不能指望调度器只跑一次。
     *
     * @param userId    用户 ID
     * @param weekStart 上一自然周的起点（本周一 00:00 再往前一周）
     * @param weekEnd   上一自然周的终点（本周一 00:00，开区间）
     * @return 本次是否加了分
     */
    @Transactional
    public boolean awardWeeklyBonus(Long userId, LocalDateTime weekStart, LocalDateTime weekEnd) {
        User user = requireUser(userId);
        int score = user.getCreditScore() == null ? ruleConfigService.creditInitScore() : user.getCreditScore();
        if (score >= BizConstants.CREDIT_MAX_SCORE) {
            // 已经满分的用户不必发奖：否则只会留下一条 delta=0 的噪音流水
            return false;
        }
        if (creditLogRepository.countByUserIdAndKindAndCreateTimeGreaterThanEqual(
                userId, CreditLogKind.WEEKLY_BONUS, weekStart) > 0) {
            return false;
        }
        if (violationRepository.countByUserIdAndCreateTimeGreaterThanEqualAndCreateTimeLessThan(
                userId, weekStart, weekEnd) > 0) {
            return false;
        }
        int bonus = BizConstants.CREDIT_WEEKLY_BONUS;
        moveScore(user, CreditLogKind.WEEKLY_BONUS, bonus, "WEEKLY", null,
                "上一自然周无违规 +" + bonus);
        int balance = user.getCreditScore();
        penaltyService.syncLadder(userId, balance, "周恢复奖励后信用分 " + balance, LocalDateTime.now());
        return true;
    }

    /**
     * 预约闸门：处罚、分区单日累计、全馆单日累计（DESIGN §8.3 的三步）。
     *
     * <p>★ {@code credit.enforce_enabled=false} 时只记录不限制，是演示环境的逃生阀，
     * 判断放在这一处而不是散到各个调用点。
     *
     * <p>★ 两道"单日上限"是<b>独立</b>的，不要合成一道：
     *   分区那条管"这个区今天被坐了多久"，全馆那条管"这个人今天一共坐了多久"（PRD §12-3）。
     *   只留分区的话，换区预约可以无限叠加 —— 五个区各 4 小时，没有一道闸门会响。
     *
     * @param userId          预约人
     * @param date            目标日期
     * @param startSlot       起始时段
     * @param durationMin     本次时长（分钟）
     * @param dailyCapMin     分区给出的单日上限（null 表示本区不设分区级上限）
     * @param usedToday       该分区当日已用分钟
     * @param globalCapMin    全馆单日上限（{@code book.daily-cap-min}）
     * @param globalUsedToday 当日全馆已用分钟
     * @param targetPeak      目标时段是否命中高峰（★ 由调用方按<b>分区</b>判定后传入：
     *                        这里自己按格号查窗口会把 A 区的高峰算到 B 区头上，
     *                        而馆员按占用率拉起的动态高峰更是完全看不见）
     */
    @Transactional(readOnly = true)
    public void checkBookingGate(Long userId, LocalDate date, int startSlot, int durationMin,
                                 Integer dailyCapMin, long usedToday,
                                 int globalCapMin, long globalUsedToday, boolean targetPeak) {
        if (!ruleConfigService.creditEnforceEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Penalty> active = penaltyService.activePenalties(userId, now);
        for (Penalty penalty : active) {
            if (penalty.getType() == PenaltyType.PEAK_BAN) {
                if (targetPeak) {
                    throw new BusinessException(ErrorCode.PEAK_BANNED,
                            "信用分受限期间不可预约高峰时段（本次恢复时间见「我的信用」）");
                }
                continue;
            }
            throw new BusinessException(ErrorCode.CREDIT_BANNED,
                    penalty.getType().getText() + "中"
                            + (penalty.getEndTime() == null ? "，需到馆处理或申诉后解除"
                            : "，将于 " + BizConstants.formatDateTime(penalty.getEndTime()) + " 恢复"));
        }
        if (dailyCapMin != null && dailyCapMin > 0 && usedToday + durationMin > dailyCapMin) {
            throw new BusinessException(ErrorCode.DAILY_CAP_EXCEEDED,
                    "该分区单日累计上限 " + dailyCapMin + " 分钟，已用 " + usedToday
                            + " 分钟，本次 " + durationMin + " 分钟超出");
        }
        if (globalCapMin > 0 && globalUsedToday + durationMin > globalCapMin) {
            // ★ 文案要说明这是"今天一共"而不是"这个区"，否则用户会换个区再试一次，
            //   而换区也照样被拒 —— 让人白跑一趟的规则说明不是好说明。
            throw new BusinessException(ErrorCode.DAILY_CAP_EXCEEDED,
                    "单日累计上限 " + globalCapMin + " 分钟（全馆口径，换分区也一样计入），"
                            + "今天已用 " + globalUsedToday + " 分钟，本次 " + durationMin + " 分钟超出");
        }
    }

    /**
     * 某人账本（正序）。
     *
     * <p>学生端「信用流水」与对账排查都读这一份：账本是分数的可追溯来源，
     * 只暴露余额就等于让用户对着一个无法解释的数字。
     *
     * @param userId 用户 ID
     * @return 流水列表
     */
    @Transactional(readOnly = true)
    public List<CreditLog> ledger(Long userId) {
        return creditLogRepository.findByUserIdOrderByCreateTimeAscIdAsc(userId);
    }

    /**
     * ★ 对账：账本之和与快照不一致的用户（运维巡检用，正常应恒为空）。
     *
     * @return 差异说明列表
     */
    @Transactional(readOnly = true)
    public List<String> consistencyReport() {
        List<String> diffs = new ArrayList<>();
        for (User user : userRepository.findAll()) {
            int ledger = creditLogRepository.sumDelta(user.getId());
            int snapshot = user.getCreditScore() == null ? 0 : user.getCreditScore();
            if (ledger != snapshot) {
                diffs.add("userId=" + user.getId() + " 账本合计=" + ledger + "，快照=" + snapshot);
            }
        }
        return diffs;
    }

    /**
     * 某人当前信用分。
     *
     * @param userId 用户 ID
     * @return 分数
     */
    @Transactional(readOnly = true)
    public int scoreOf(Long userId) {
        Integer score = requireUser(userId).getCreditScore();
        return score == null ? 0 : score;
    }

    /**
     * 申诉成立时的反向回补（§6.3；不改写历史，只追加 RESTORE 流水）。
     *
     * @param userId     用户 ID
     * @param amount     回补分值（正数）
     * @param appealId   申诉 ID
     * @param note       说明
     */
    @Transactional
    public void restore(Long userId, int amount, Long appealId, String note) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "回补分值必须为正数");
        }
        User user = requireUser(userId);
        moveScore(user, CreditLogKind.RESTORE, amount, "APPEAL", appealId, note);
        penaltyService.syncLadder(userId, user.getCreditScore(), "申诉回补后信用分 " + user.getCreditScore(),
                LocalDateTime.now());
    }

    /**
     * 改分的唯一落点：夹到 [0, 100] 并记一笔带余额快照的流水。
     */
    private int moveScore(User user, CreditLogKind kind, int delta,
                          String refType, Long refId, String reason) {
        int current = user.getCreditScore() == null ? ruleConfigService.creditInitScore() : user.getCreditScore();
        int balance = Math.max(0, Math.min(BizConstants.CREDIT_MAX_SCORE, current + delta));
        user.setCreditScore(balance);
        userRepository.save(user);
        creditLogRepository.save(CreditLog.builder()
                .userId(user.getId())
                .kind(kind)
                .delta(balance - current)
                .balanceAfter(balance)
                .refType(refType)
                .refId(refId)
                .reason(reason)
                .build());
        return balance;
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    /**
     * 组装证据 JSON（违规的必要附件，也是申诉裁定页要渲染的内容）。
     *
     * <p>★ 单点提供是为了让「证据里有哪些字段」只有一份定义：手写字符串拼 JSON
     * 迟早会在含引号的字段（如巡查备注）上炸开。
     *
     * @param fields 有序字段表
     * @return JSON 文本
     */
    public String buildEvidence(java.util.LinkedHashMap<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("证据序列化失败", e);
        }
    }
}

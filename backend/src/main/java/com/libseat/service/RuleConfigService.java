package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.RuleKeys;
import com.libseat.entity.RuleConfig;
import com.libseat.enums.DayType;
import com.libseat.enums.UserRole;
import com.libseat.repository.RuleConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则配置中心：业务阈值的唯一运行时来源（「后台可配、不发版」）。
 *
 * <p>读路径走内存快照，不在请求里查配置表；写路径立即重建快照并用 60 秒 TTL 兜底
 * （单机部署，无需分布式缓存失效通知）。
 *
 * <p>解析失败一律回落到调用方传入的兜底值并打 WARN —— 配置错误不该让整个系统 500，
 * 但也绝不静默生效，日志里必须能查到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigService {

    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final long TTL_MILLIS = 60_000L;

    private final RuleConfigRepository ruleConfigRepository;

    private final AuditService auditService;

    /** scope|scopeId|rkey → rvalue 的原始字符串快照。 */
    private volatile Map<String, String> snapshot = Map.of();

    private volatile long loadedAt;

    @PostConstruct
    void init() {
        refresh();
    }

    /** 重建快照。 */
    public synchronized void refresh() {
        Map<String, String> next = new HashMap<>();
        for (RuleConfig rule : ruleConfigRepository.findAllByOrderByScopeAscScopeIdAscRkeyAsc()) {
            next.put(composeKey(rule.getScope(), rule.getScopeId(), rule.getRkey()), rule.getRvalue());
        }
        snapshot = Collections.unmodifiableMap(next);
        loadedAt = System.currentTimeMillis();
        log.info("规则配置快照已刷新，共 {} 项", next.size());
    }

    private String composeKey(String scope, String scopeId, String rkey) {
        return scope + "|" + (scopeId == null ? "" : scopeId) + "|" + rkey;
    }

    private String raw(String scope, String scopeId, String rkey) {
        if (System.currentTimeMillis() - loadedAt > TTL_MILLIS) {
            refresh();
        }
        return snapshot.get(composeKey(scope, scopeId, rkey));
    }

    /**
     * 读取 GLOBAL 作用域的字符串值。
     *
     * @param rkey     规则键
     * @param fallback 兜底值（配置缺失时返回）
     * @return 配置值
     */
    public String getString(String rkey, String fallback) {
        String value = raw(SCOPE_GLOBAL, "", rkey);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /**
     * 读取 GLOBAL 作用域的整数值。
     *
     * @param rkey     规则键
     * @param fallback 兜底值
     * @return 配置值；非法数字时回落兜底
     */
    public int getInt(String rkey, int fallback) {
        String value = getString(rkey, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("规则 {} 的值「{}」不是整数，回落兜底值 {}", rkey, value, fallback);
            return fallback;
        }
    }

    /**
     * 读取 GLOBAL 作用域的布尔值（true/false/1/0/yes/no）。
     *
     * @param rkey     规则键
     * @param fallback 兜底值
     * @return 配置值
     */
    public boolean getBool(String rkey, boolean fallback) {
        String value = getString(rkey, null);
        if (value == null) {
            return fallback;
        }
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> {
                log.warn("规则 {} 的值「{}」不是布尔值，回落兜底值 {}", rkey, value, fallback);
                yield fallback;
            }
        };
    }

    /**
     * 读取 GLOBAL 作用域的整数列表（逗号分隔）。
     *
     * @param rkey     规则键
     * @param fallback 兜底列表
     * @return 升序整数列表；解析失败回落兜底
     */
    public List<Integer> getIntList(String rkey, List<Integer> fallback) {
        String value = getString(rkey, null);
        if (value == null) {
            return fallback;
        }
        List<Integer> parsed = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                log.warn("规则 {} 的值「{}」含非整数项，回落兜底值 {}", rkey, value, fallback);
                return fallback;
            }
        }
        return parsed.isEmpty() ? fallback : List.copyOf(parsed);
    }

    // ==================== 已接线规则的领域读法 ====================

    /** 签到窗口：开始前多少分钟。 */
    public int checkInBeforeMin() {
        return getInt(RuleKeys.BOOK_CHECK_IN_BEFORE_MIN, BizConstants.CHECK_IN_BEFORE_MIN);
    }

    /** 签到窗口：开始后多少分钟。 */
    public int checkInAfterMin() {
        return getInt(RuleKeys.BOOK_CHECK_IN_AFTER_MIN, BizConstants.CHECK_IN_AFTER_MIN);
    }

    /** 可提前预约天数（含当天）。 */
    public int advanceDays() {
        return getInt(RuleKeys.BOOK_ADVANCE_DAYS, BizConstants.ADVANCE_DAYS);
    }

    /** 单日累计上限（分钟，全馆口径，挂在用户身上）。 */
    public int dailyCapMin() {
        return getInt(RuleKeys.BOOK_DAILY_CAP_MIN, BizConstants.DAILY_CAP_MIN);
    }

    /** 考试周的单日累计上限（分钟，PRD §12-3 的 8 小时）。 */
    public int dailyCapExamMin() {
        return getInt(RuleKeys.BOOK_DAILY_CAP_EXAM_MIN, BizConstants.DAILY_CAP_EXAM_MIN);
    }

    /**
     * 按日型给出当天生效的全馆单日累计上限（PRD §12-3「6 小时，考试周 8 小时」）。
     *
     * <p>★ 日型必须从 {@code OpenRuleService#resolve} 取，不要在调用处自己看星期：
     *   考试周是管理员在开放日历上标的，用 {@code DayOfWeek} 猜会把考试周算成普通工作日，
     *   那正是"规则写在配置里但没人按正确口径读它"的老毛病。
     *
     * @param dayType 当天日型（null 按普通日处理）
     * @return 生效上限（分钟）
     */
    public int dailyCapMin(DayType dayType) {
        return dayType == DayType.EXAM ? dailyCapExamMin() : dailyCapMin();
    }

    /** 预约默认时长（小时）。 */
    public int defaultDuration() {
        return getInt(RuleKeys.BOOK_DEFAULT_DURATION, BizConstants.DEFAULT_DURATION);
    }

    /** 可选时长列表（小时）。 */
    public List<Integer> allowedDurations() {
        return getIntList(RuleKeys.BOOK_ALLOWED_DURATIONS, BizConstants.ALLOWED_DURATIONS);
    }

    /**
     * 按角色给出可选时长（PRD §12-2「单次上限 4 小时（教师 6 小时）」）。
     *
     * <p>★ 校验与选项必须走这一个函数。以前教师上限只在高峰压缩那一步被读到，
     *   可选列表却一律是 1~4 小时，于是"教师可约 6 小时"这条规则在系统里**不存在**：
     *   教师挑不到 5/6 小时，硬调接口也会被"时长只能选择 1/2/3/4 小时"拦下。
     *
     * <p>教师的放宽方式是"在配置列表之上追加"，不是另起一张表：
     * 管理员把 {@code book.allowed-durations} 改成 [1,2] 时，教师拿到的就是 [1,2,3..6]，
     * 仍然尊重配置里刻意收紧的那些档位以下部分。
     *
     * @param role 当前用户角色（null 按普通用户处理）
     * @return 可选时长（小时，升序）
     */
    public List<Integer> allowedDurations(UserRole role) {
        List<Integer> base = allowedDurations();
        if (role != UserRole.TEACHER || base.isEmpty()) {
            return base;
        }
        int baseMax = Collections.max(base);
        int teacherHours = maxDurationTeacherMin() / 60;
        if (teacherHours <= baseMax) {
            return base;
        }
        List<Integer> extended = new ArrayList<>(base);
        for (int hour = baseMax + 1; hour <= teacherHours; hour++) {
            extended.add(hour);
        }
        Collections.sort(extended);
        return List.copyOf(extended);
    }

    /** ★ 信用闸门总开关：false 时违规照记、分数照扣，但不阻断预约（演示逃生阀）。 */
    public boolean creditEnforceEnabled() {
        return getBool(RuleKeys.CREDIT_ENFORCE_ENABLED, true);
    }

    /** 初始信用分。 */
    public int creditInitScore() {
        return getInt(RuleKeys.CREDIT_INIT_SCORE, BizConstants.CREDIT_INIT_SCORE);
    }

    /** 触发「高峰禁约」的分数上界。 */
    public int creditPeakBanUnder() {
        return getInt(RuleKeys.CREDIT_PEAK_BAN_UNDER, BizConstants.CREDIT_PEAK_BAN_UNDER);
    }

    /** 触发「暂停 3 天」的分数上界。 */
    public int creditBan3dUnder() {
        return getInt(RuleKeys.CREDIT_BAN_3D_UNDER, BizConstants.CREDIT_BAN_3D_UNDER);
    }

    /** 触发「暂停 7 天」的分数上界。 */
    public int creditBan7dUnder() {
        return getInt(RuleKeys.CREDIT_BAN_7D_UNDER, BizConstants.CREDIT_BAN_7D_UNDER);
    }

    /** 触发「暂停至学期末」的分数上界（默认 1，即 0 分）。 */
    public int creditSemesterUnder() {
        return getInt(RuleKeys.CREDIT_SEMESTER_UNDER, BizConstants.CREDIT_SEMESTER_UNDER);
    }

    /** 「暂停 3 天」的天数。 */
    public int creditBan3dDays() {
        return getInt(RuleKeys.CREDIT_BAN_3D_DAYS, BizConstants.CREDIT_BAN_3D_DAYS);
    }

    /** 「暂停 7 天」的天数。 */
    public int creditBan7dDays() {
        return getInt(RuleKeys.CREDIT_BAN_7D_DAYS, BizConstants.CREDIT_BAN_7D_DAYS);
    }

    /** 连续主动结束的恢复加分。 */
    public int creditStreakBonus() {
        return getInt(RuleKeys.CREDIT_STREAK_BONUS, BizConstants.CREDIT_STREAK_BONUS);
    }

    /** 连续主动结束多少档算一次奖励。 */
    public int creditStreakSize() {
        return getInt(RuleKeys.CREDIT_STREAK_SIZE, BizConstants.CREDIT_STREAK_SIZE);
    }

    // ==================== 高峰调控（T-V2-18） ====================

    /** 动态高峰占用率阈值（百分数）。 */
    public int peakDynamicThreshold() {
        return getInt(RuleKeys.PEAK_DYNAMIC_THRESHOLD, BizConstants.PEAK_DYNAMIC_THRESHOLD);
    }

    /** 动态高峰：连续超阈多少分钟才认定。 */
    public int peakDynamicSustainMin() {
        return getInt(RuleKeys.PEAK_DYNAMIC_SUSTAIN_MIN, BizConstants.PEAK_DYNAMIC_SUSTAIN_MIN);
    }

    /** 动态高峰：认定后至少保持多少分钟。 */
    public int peakDynamicHoldMin() {
        return getInt(RuleKeys.PEAK_DYNAMIC_HOLD_MIN, BizConstants.PEAK_DYNAMIC_HOLD_MIN);
    }

    /** 高峰时段单次时长上限（分钟）。 */
    /** 高峰降级：允许同时在途的预约请求数（PRD §13.4）。 */
    public int degradeMaxInflight() {
        return getInt(RuleKeys.PEAK_DEGRADE_MAX_INFLIGHT, BizConstants.DEGRADE_MAX_INFLIGHT);
    }

    /** 高峰降级：排队等待上限（毫秒）。 */
    public long degradeWaitMs() {
        return getInt(RuleKeys.PEAK_DEGRADE_WAIT_MS, BizConstants.DEGRADE_WAIT_MS);
    }

    public int peakCompressMin() {
        return getInt(RuleKeys.PEAK_COMPRESS_MIN, BizConstants.PEAK_COMPRESS_MIN);
    }

    /** 高峰时段的提前开放时刻（{@code HH:mm}，解析失败回退兜底值）。 */
    public LocalTime peakOpenTime() {
        return parseTime(getString(RuleKeys.PEAK_OPEN_TIME, BizConstants.PEAK_OPEN_TIME));
    }

    /** 教师单次时长上限（分钟）。 */
    public int maxDurationTeacherMin() {
        return getInt(RuleKeys.BOOK_MAX_DURATION_TEACHER_MIN, BizConstants.MAX_DURATION_TEACHER_MIN);
    }

    /** 候补 offer 的确认窗口（分钟）。 */
    public int waitlistOfferMin() {
        return getInt(RuleKeys.WAITLIST_OFFER_MIN, BizConstants.WAITLIST_OFFER_MIN);
    }

    /** 同一用户最多能挂几条活动候补。 */
    public int waitlistMaxActive() {
        return getInt(RuleKeys.WAITLIST_MAX_ACTIVE, BizConstants.WAITLIST_MAX_ACTIVE);
    }

    /** 暂离到期前多少分钟发站内提醒。 */
    public int noticeLeaveRemindMin() {
        return getInt(RuleKeys.NOTICE_LEAVE_REMIND_MIN, BizConstants.NOTICE_LEAVE_REMIND_MIN);
    }

    /** 签到后连续使用无暂离多少分钟即疑似占座。 */
    public int patrolSuspectMin() {
        return getInt(RuleKeys.PATROL_SUSPECT_MIN, BizConstants.PATROL_SUSPECT_MIN);
    }

    /** 疑似占座要求的剩余时长下限（分钟）。 */
    public int patrolMinLeftMin() {
        return getInt(RuleKeys.PATROL_SUSPECT_MIN_LEFT, BizConstants.PATROL_MIN_LEFT_MIN);
    }

    /** 组队签到后多少分钟未确认入座即登记任务。 */
    public int patrolGroupAbsentMin() {
        return getInt(RuleKeys.PATROL_GROUP_ABSENT_MIN, BizConstants.PATROL_GROUP_ABSENT_MIN);
    }

    /** 提醒后多少分钟内再犯即升为 HIGH。 */
    public int patrolEscalateMin() {
        return getInt(RuleKeys.PATROL_ESCALATE_MIN, BizConstants.PATROL_ESCALATE_MIN);
    }

    /**
     * 把配置里的时刻文本解析成 {@link LocalTime}，写错不影响服务启动。
     *
     * <p>★ 不抛异常是有意为之：一个手抖填成 "2200" 的时刻不该让整站起不来，
     *   回退兜底值并记一条 warn，管理员在规则页上看得见原值。
     *
     * @param text 配置值
     * @return 解析结果，失败为兜底值
     */
    private LocalTime parseTime(String text) {
        try {
            return LocalTime.parse(text);
        } catch (RuntimeException e) {
            log.warn("规则里的时刻格式不对，已回退兜底值：{}（兜底 {}）", text, BizConstants.PEAK_OPEN_TIME);
            return LocalTime.parse(BizConstants.PEAK_OPEN_TIME);
        }
    }

    // ==================== 管理端读写 ====================

    /**
     * 列出规则。
     *
     * @param rgroup 规则分组，为空表示全部
     * @return 规则列表
     */
    @Transactional(readOnly = true)
    public List<RuleConfig> list(String rgroup) {
        return (rgroup == null || rgroup.isBlank())
                ? ruleConfigRepository.findAllByOrderByScopeAscScopeIdAscRkeyAsc()
                : ruleConfigRepository.findByRgroupOrderByScopeAscScopeIdAscRkeyAsc(rgroup.trim().toUpperCase());
    }

    /**
     * 更新一条规则的值（★ 只允许改已存在的键，新增键由迁移脚本引入）。
     *
     * @param scope   作用域
     * @param scopeId 作用域对象 ID（GLOBAL 传空）
     * @param rkey    规则键
     * @param value   新值
     * @param actorId 操作人
     * @return 更新后的规则
     */
    @Transactional
    public RuleConfig update(String scope, String scopeId, String rkey, String value, Long actorId) {
        String normalizedScope = (scope == null || scope.isBlank()) ? SCOPE_GLOBAL : scope.trim().toUpperCase();
        String normalizedScopeId = (scopeId == null) ? "" : scopeId.trim();
        RuleConfig rule = ruleConfigRepository
                .findByScopeAndScopeIdAndRkey(normalizedScope, normalizedScopeId, rkey.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR,
                        "规则不存在：" + rkey + "（新增规则需通过版本化迁移脚本引入）"));
        if (Boolean.FALSE.equals(rule.getEditable())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该规则不允许修改：" + rule.getRkey());
        }
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "规则值不能为空：" + rule.getRkey());
        }
        String trimmed = value.trim();
        validate(rule.getValueType(), trimmed, rule.getRkey());

        String oldValue = rule.getRvalue();
        rule.setRvalue(trimmed);
        rule.setUpdatedBy(actorId);
        RuleConfig saved = ruleConfigRepository.save(rule);
        refresh();
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("rkey", saved.getRkey());
        detail.put("oldValue", oldValue);
        detail.put("newValue", saved.getRvalue());
        auditService.record(AuditService.ACTION_RULE_UPDATE, "RULE", saved.getId(), detail);
        log.info("规则已更新：{}={}（操作人 userId={}）", saved.getRkey(), saved.getRvalue(), actorId);
        return saved;
    }

    private void validate(String valueType, String value, String rkey) {
        String type = (valueType == null) ? "STRING" : valueType.trim().toUpperCase();
        switch (type) {
            case "INT" -> requireInt(value, rkey);
            case "INT_LIST" -> {
                for (String part : value.split(",")) {
                    if (part.isBlank()) {
                        throw new BusinessException(ErrorCode.PARAM_ERROR, "规则 " + rkey + " 的列表含空项");
                    }
                    requireInt(part.trim(), rkey);
                }
            }
            case "BOOL" -> {
                String normalized = value.toLowerCase();
                if (!List.of("true", "false", "1", "0", "yes", "no").contains(normalized)) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR,
                            "规则 " + rkey + " 需要布尔值（true/false）");
                }
            }
            case "TIME" -> {
                if (!value.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR,
                            "规则 " + rkey + " 需要 HH:mm 格式的时间");
                }
            }
            default -> {
                // STRING：不做格式约束
            }
        }
    }

    private void requireInt(String value, String rkey) {
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "规则 " + rkey + " 需要整数，实际为「" + value + "」");
        }
    }
}

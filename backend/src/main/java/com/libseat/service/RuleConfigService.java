package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.RuleKeys;
import com.libseat.entity.RuleConfig;
import com.libseat.repository.RuleConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 预约默认时长（小时）。 */
    public int defaultDuration() {
        return getInt(RuleKeys.BOOK_DEFAULT_DURATION, BizConstants.DEFAULT_DURATION);
    }

    /** 可选时长列表（小时）。 */
    public List<Integer> allowedDurations() {
        return getIntList(RuleKeys.BOOK_ALLOWED_DURATIONS, BizConstants.ALLOWED_DURATIONS);
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

        rule.setRvalue(trimmed);
        rule.setUpdatedBy(actorId);
        RuleConfig saved = ruleConfigRepository.save(rule);
        refresh();
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

package com.libseat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.common.PageResult;
import com.libseat.dto.AuditDTO;
import com.libseat.dto.RuleDTO;
import com.libseat.entity.RuleConfig;
import com.libseat.entity.User;
import com.libseat.repository.RuleConfigRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则变更历史（TASKS-V2 T-V2-22）。
 *
 * <p>★ 历史只读审计表，不在规则表旁边另建一张「变更表」：
 * 审计里已经写了 {@code oldValue}/{@code newValue}（S0 就这么定的），再造一份存储就是两个真相源，
 * 而两处不一致时没人能说出哪个是真的。这里做的只是把 JSON 摊平成表格行。
 *
 * <p>★ 扫描上限 {@link #SCAN_LIMIT} 条并如实告知：规则改动是低频治理动作，
 * 演示与真实运营都远够；真要长期追溯，应该给审计加分区与时间筛选，而不是把上限调大。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleHistoryService {

    /** 最多回看多少条规则变更（按时间倒序取）。 */
    private static final int SCAN_LIMIT = 500;

    private final AuditService auditService;

    private final RuleConfigRepository ruleConfigRepository;

    private final UserRepository userRepository;

    private final ObjectMapper objectMapper;

    /**
     * 规则变更历史。
     *
     * @param rkey 只看某个键，空表示全部
     * @return 新的在前
     */
    @Transactional(readOnly = true)
    public List<RuleDTO.RuleHistoryVO> history(String rkey) {
        PageResult<AuditDTO.AuditVO> page = auditService.search(
                null, null, AuditService.ACTION_RULE_UPDATE, 1, SCAN_LIMIT);
        List<AuditDTO.AuditVO> rows = page.getList().stream()
                .filter(logEntry -> StringUtils.hasText(logEntry.getDetail()))
                .toList();
        Map<Long, RuleConfig> rules = rulesById(rows);
        Map<Long, User> people = usersById(rows);

        List<RuleDTO.RuleHistoryVO> out = new ArrayList<>(rows.size());
        for (AuditDTO.AuditVO logEntry : rows) {
            JsonNode detail = parseDetail(logEntry);
            if (detail == null) {
                continue;
            }
            String key = detail.path("rkey").asText(null);
            if (StringUtils.hasText(rkey) && !rkey.trim().equals(key)) {
                continue;
            }
            RuleConfig rule = rules.get(idOf(logEntry));
            User operator = people.get(logEntry.getActorId());
            out.add(RuleDTO.RuleHistoryVO.builder()
                    .logId(logEntry.getId())
                    .createTime(logEntry.getCreateTime())
                    .rkey(key)
                    .description(rule == null ? null : rule.getDescription())
                    .rgroup(rule == null ? null : rule.getRgroup())
                    .scope(rule == null ? null : rule.getScope())
                    .scopeId(rule == null ? null : rule.getScopeId())
                    .oldValue(detail.path("oldValue").asText(null))
                    .newValue(detail.path("newValue").asText(null))
                    .operatorId(logEntry.getActorId())
                    .operatorName(operator == null ? null
                            : (operator.getRealName() == null ? operator.getUsername() : operator.getRealName()))
                    .operatorRole(logEntry.getActorRole())
                    .build());
        }
        return out;
    }

    private Map<Long, RuleConfig> rulesById(List<AuditDTO.AuditVO> rows) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AuditDTO.AuditVO row : rows) {
            Long id = idOf(row);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ruleConfigRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(RuleConfig::getId, rule -> rule));
    }

    private Map<Long, User> usersById(List<AuditDTO.AuditVO> rows) {
        Set<Long> ids = rows.stream().map(AuditDTO.AuditVO::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, user -> user));
    }

    /** 审计的 target_id 是字符串列（存的是 RuleConfig 主键），解析不出就当「规则已不存在」。 */
    private Long idOf(AuditDTO.AuditVO row) {
        try {
            return row.getTargetId() == null ? null : Long.valueOf(row.getTargetId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode parseDetail(AuditDTO.AuditVO row) {
        try {
            return objectMapper.readTree(row.getDetail());
        } catch (Exception e) {
            // 一条坏 detail 不能拖垮整页历史；但也不能假装看见过它
            log.warn("规则变更审计 detail 解析失败，跳过该条：logId={}, err={}", row.getId(), e.getMessage());
            return null;
        }
    }
}

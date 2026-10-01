package com.libseat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.common.BizConstants;
import com.libseat.common.PageResult;
import com.libseat.dto.AuditDTO;
import com.libseat.entity.AuditLog;
import com.libseat.repository.AuditLogRepository;
import com.libseat.security.UserContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志服务（★ 审计失败绝不允许影响主业务）。
 *
 * <p>两点设计说明：
 * <ol>
 *   <li>用 {@code REQUIRES_NEW} 独立事务 + 内部 try-catch：审计写失败只打 ERROR 日志，
 *       不能把已经成功的业务操作回滚掉（治理日志不该有否决业务的权力）。</li>
 *   <li>因此调用点必须放在业务写成功之后；若业务随后回滚，会留下一条「尝试过」的审计记录，
 *       这对治理场景是可接受且更有价值的（记录企图比记录结果更重要）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 动作码：规则变更。 */
    public static final String ACTION_RULE_UPDATE = "RULE_UPDATE";

    /** 动作码：管理员强制释放。 */
    public static final String ACTION_RESERVATION_RELEASE = "RESERVATION_RELEASE";

    /** 动作码：修改密码。 */
    public static final String ACTION_PASSWORD_CHANGE = "PASSWORD_CHANGE";

    /** 动作码：删除楼层。 */
    public static final String ACTION_FLOOR_DELETE = "FLOOR_DELETE";

    /** 动作码：删除座位。 */
    public static final String ACTION_SEAT_DELETE = "SEAT_DELETE";

    /** 动作码：新增或编辑分区（含状态切换）。 */
    public static final String ACTION_ZONE_SAVE = "ZONE_SAVE";

    /** 动作码：删除分区。 */
    public static final String ACTION_ZONE_DELETE = "ZONE_DELETE";

    /** 动作码：新增或编辑桌位。 */
    public static final String ACTION_TABLE_SAVE = "TABLE_SAVE";

    /** 动作码：删除桌位。 */
    public static final String ACTION_TABLE_DELETE = "TABLE_DELETE";

    /** 动作码 → 中文文案（唯一来源，前端直接展示，不在两处写映射）。 */
    private static final Map<String, String> ACTION_TEXT = Map.ofEntries(
            Map.entry(ACTION_RULE_UPDATE, "修改规则配置"),
            Map.entry(ACTION_RESERVATION_RELEASE, "强制释放预约"),
            Map.entry(ACTION_PASSWORD_CHANGE, "修改密码"),
            Map.entry(ACTION_FLOOR_DELETE, "删除楼层"),
            Map.entry(ACTION_SEAT_DELETE, "删除座位"),
            Map.entry(ACTION_ZONE_SAVE, "维护分区"),
            Map.entry(ACTION_ZONE_DELETE, "删除分区"),
            Map.entry(ACTION_TABLE_SAVE, "维护桌位"),
            Map.entry(ACTION_TABLE_DELETE, "删除桌位")
    );

    private final AuditLogRepository auditLogRepository;

    private final ObjectMapper objectMapper;

    /**
     * 记录一次治理动作。
     *
     * @param action     动作码（本类的 ACTION_* 常量）
     * @param targetType 对象类型
     * @param targetId   对象 ID（可为 null）
     * @param detail     明细键值（旧值/新值/原因等），可为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Object targetId, Map<String, Object> detail) {
        try {
            AuditLog entry = AuditLog.builder()
                    .actorId(UserContext.getUserId())
                    .actorRole(UserContext.getRole() == null ? null : UserContext.getRole().name())
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId == null ? null : String.valueOf(targetId))
                    .detail(toJson(detail))
                    .createTime(LocalDateTime.now())
                    .build();
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("审计日志写入失败：action={}, target={}#{}", action, targetType, targetId, e);
        }
    }

    /**
     * 便捷重载：记录一对旧值 / 新值。
     *
     * @param action     动作码
     * @param targetType 对象类型
     * @param targetId   对象 ID
     * @param key1       第一个键
     * @param value1     第一个值
     * @param key2       第二个键
     * @param value2     第二个值
     */
    public void record(String action, String targetType, Object targetId,
                       String key1, Object value1, String key2, Object value2) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(key1, value1);
        detail.put(key2, value2);
        record(action, targetType, targetId, detail);
    }

    /**
     * 分页查询审计日志。
     *
     * @param from   起始时间，可空
     * @param to     结束时间，可空
     * @param action 动作码，可空
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     * @return 分页结果，按时间倒序
     */
    @Transactional(readOnly = true)
    public PageResult<AuditDTO.AuditVO> search(LocalDateTime from, LocalDateTime to,
                                               String action, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createTime"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createTime"), to));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.trim().toUpperCase()));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<AuditLog> result = auditLogRepository.findAll(spec, PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "createTime").and(Sort.by(Sort.Direction.DESC, "id"))));
        List<AuditDTO.AuditVO> list = new ArrayList<>();
        for (AuditLog entry : result.getContent()) {
            list.add(AuditDTO.AuditVO.builder()
                    .id(entry.getId())
                    .actorId(entry.getActorId())
                    .actorRole(entry.getActorRole())
                    .action(entry.getAction())
                    .actionText(actionText(entry.getAction()))
                    .targetType(entry.getTargetType())
                    .targetId(entry.getTargetId())
                    .detail(entry.getDetail())
                    .createTime(BizConstants.formatDateTime(entry.getCreateTime()))
                    .build());
        }
        return PageResult.<AuditDTO.AuditVO>builder()
                .list(list)
                .total(result.getTotalElements())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    /**
     * 动作码的中文文案。
     *
     * @param action 动作码
     * @return 文案；未知动作回落到动作码本身
     */
    public static String actionText(String action) {
        return ACTION_TEXT.getOrDefault(action, action);
    }

    private String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("审计明细序列化失败，退化为 toString：{}", e.getMessage());
            return String.valueOf(detail);
        }
    }
}

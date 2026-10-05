package com.libseat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.AppealDTO;
import com.libseat.entity.Appeal;
import com.libseat.entity.Violation;
import com.libseat.enums.AppealStatus;
import com.libseat.enums.UserRole;
import com.libseat.enums.ViolationStatus;
import com.libseat.repository.AppealRepository;
import com.libseat.repository.ViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 违规明细读取与撤销（DESIGN §12.1 S11、§12.2 F8）。
 *
 * <p>★ 撤销违规只有一条路径能回补分数：本类的 {@link #revoke} 与申诉成立共用
 * {@link CreditService#restore}，且两者都会先把违规推到 {@code REVOKED}。
 * 违规处于申诉中（PENDING/ESCALATED）时直接拒绝现场撤销 —— 否则「申诉裁定」和「馆员撤销」
 * 两个入口都能补分，同一笔扣分会被回补两次，账本立刻不平。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationService {

    /**
     * 证据键 → 中文标签（PRD §9.4「裁定页要能看懂证据」）。
     *
     * <p>未登记的键按原始键名输出，而不是丢弃：新增证据字段（如巡查照片）在最坏情况下
     * 也只是标签不好看，不会让裁定人看不到依据。
     */
    private static final Map<String, String> EVIDENCE_LABELS = new LinkedHashMap<>();

    static {
        EVIDENCE_LABELS.put("rule", "触发规则");
        EVIDENCE_LABELS.put("reserveDate", "预约日期");
        EVIDENCE_LABELS.put("startSlot", "起始时段序号");
        EVIDENCE_LABELS.put("slotLabel", "预约时段");
        EVIDENCE_LABELS.put("deadline", "签到截止时刻");
        EVIDENCE_LABELS.put("leaveId", "暂离记录");
        EVIDENCE_LABELS.put("seatId", "座位");
        EVIDENCE_LABELS.put("leaveStart", "暂离开始");
        EVIDENCE_LABELS.put("leaveDeadline", "应归时刻");
        EVIDENCE_LABELS.put("judgedAt", "裁定时刻");
        EVIDENCE_LABELS.put("operatorId", "裁定人");
        EVIDENCE_LABELS.put("patrolId", "巡查任务");
        EVIDENCE_LABELS.put("note", "现场说明");
        EVIDENCE_LABELS.put("photoUrl", "现场照片");
        // ★ 巡查疑点的判定输入（T-V2-21）：看板要回答「系统凭什么报警」，
        //   漏配一个键只会退化成原始英文名，所以疑点里出现什么就在这里配什么。
        EVIDENCE_LABELS.put("thresholdMin", "阈值（分钟）");
        EVIDENCE_LABELS.put("usedMin", "已连续使用（分钟）");
        EVIDENCE_LABELS.put("checkInTime", "签到时刻");
        EVIDENCE_LABELS.put("plannedEnd", "预约结束时刻");
        EVIDENCE_LABELS.put("onLeave", "正在暂离");
        EVIDENCE_LABELS.put("overdueMin", "已超时（分钟）");
        EVIDENCE_LABELS.put("groupId", "队伍");
        EVIDENCE_LABELS.put("memberUserId", "被登记成员");
        EVIDENCE_LABELS.put("leaderCheckInTime", "组长签到时刻");
        EVIDENCE_LABELS.put("leaderCheckedInForOthers", "组长已代全队签到");
        EVIDENCE_LABELS.put("status", "预约状态");
        EVIDENCE_LABELS.put("registeredBy", "登记人");
        EVIDENCE_LABELS.put("reservationId", "关联预约");
        EVIDENCE_LABELS.put("handledBy", "处置人");
        EVIDENCE_LABELS.put("handleNote", "处置说明");
    }

    private final ViolationRepository violationRepository;

    private final AppealRepository appealRepository;

    private final CreditService creditService;

    private final AuditService auditService;

    private final ObjectMapper objectMapper;

    /**
     * 学生面「我的违规」（S11）。
     *
     * @param userId 当前用户
     * @return 违规明细（新的在前，含申诉状态与可动作）
     */
    @Transactional(readOnly = true)
    public List<AppealDTO.ViolationVO> listMine(Long userId) {
        return decorate(violationRepository.findByUserIdOrderByIdDesc(userId), userId);
    }

    /**
     * 馆员面违规查询（F8）。
     *
     * @param userId 指定用户；null 表示全馆
     * @param status 指定状态；null 表示不限
     * @return 违规明细（新的在前）
     */
    @Transactional(readOnly = true)
    public List<AppealDTO.ViolationVO> listForStaff(Long userId, ViolationStatus status) {
        List<Violation> source = userId == null
                ? violationRepository.findAllByOrderByIdDesc()
                : violationRepository.findByUserIdOrderByIdDesc(userId);
        List<Violation> filtered = (status == null) ? source
                : source.stream().filter(v -> v.getStatus() == status).toList();
        // 馆员面看到的「能不能申诉」按违规人自己的视角算，不然会误报时限
        return decorate(filtered, null);
    }

    /**
     * 直接撤销违规：回补分数 + 违规置 REVOKED + 写审计。
     *
     * <p>这是错过 24 小时申诉窗口的唯一救济入口（PRD §9.4），因此必须留痕：理由必填。
     *
     * @param violationId 违规 ID
     * @param reason      撤销理由
     * @param operatorId  操作人
     * @param role        操作人角色（写进审计，便于事后追责）
     * @return 撤销后的违规
     */
    @Transactional
    public AppealDTO.ViolationVO revoke(Long violationId, String reason, Long operatorId, UserRole role) {
        Violation violation = requireViolation(violationId);
        if (violation.getStatus() == ViolationStatus.REVOKED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该违规已撤销，无需重复操作");
        }
        Optional<Appeal> appeal = appealRepository.findByViolationId(violationId);
        if (appeal.isPresent() && appeal.get().getStatus().judgeable()) {
            // ★ 两个入口都能补分 = 同一笔扣分会被补两次，账本当场不平
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该违规正在申诉流程中，请到申诉队列裁定，不要从这里撤销");
        }
        LocalDateTime now = LocalDateTime.now();
        creditService.restore(violation.getUserId(), -violation.getScoreDelta(), appeal.map(Appeal::getId)
                .orElse(null), "馆员撤销违规：" + violation.getType().getText());
        violation.setStatus(ViolationStatus.REVOKED);
        violation.setRevokeTime(now);
        violation.setRevokeReason(trim(reason, 200));
        violationRepository.save(violation);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("violationId", violationId);
        detail.put("userId", violation.getUserId());
        detail.put("type", violation.getType().name());
        detail.put("restoredScore", -violation.getScoreDelta());
        detail.put("reason", reason);
        detail.put("operatorRole", role == null ? null : role.name());
        auditService.record(AuditService.ACTION_VIOLATION_REVOKE, "VIOLATION", violationId, detail);
        log.info("违规已撤销：violationId={}, userId={}, 回补={}, 操作人={}",
                violationId, violation.getUserId(), -violation.getScoreDelta(), operatorId);
        return toVO(violation, appeal.orElse(null), violation.getUserId(), now);
    }

    /**
     * 渲染证据 JSON 的可读视图（要点「裁定页渲染违规 evidence JSON 的可读版本」）。
     *
     * @param evidenceJson 证据 JSON
     * @return 有序可读项；解析失败时退化为一条原文，绝不吞掉证据
     */
    public List<AppealDTO.EvidenceItem> evidenceView(String evidenceJson) {
        List<AppealDTO.EvidenceItem> items = new ArrayList<>();
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return items;
        }
        try {
            JsonNode root = objectMapper.readTree(evidenceJson);
            if (!root.isObject()) {
                items.add(new AppealDTO.EvidenceItem("raw", "原始证据", evidenceJson));
                return items;
            }
            root.fields().forEachRemaining(entry -> items.add(new AppealDTO.EvidenceItem(
                    entry.getKey(),
                    EVIDENCE_LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                    entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString())));
        } catch (Exception e) {
            // 证据是裁定依据，宁可原样展示也不能因为一个标签没配就消失
            log.warn("证据 JSON 解析失败，退化为原文展示：{}", e.getMessage());
            items.clear();
            items.add(new AppealDTO.EvidenceItem("raw", "原始证据", evidenceJson));
        }
        return items;
    }

    private List<AppealDTO.ViolationVO> decorate(List<Violation> violations, Long subjectUserId) {
        List<AppealDTO.ViolationVO> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Violation violation : violations) {
            Appeal appeal = appealRepository.findByViolationId(violation.getId()).orElse(null);
            result.add(toVO(violation, appeal, subjectUserId, now));
        }
        return result;
    }

    /**
     * 单条违规视图（申诉队列复用同一份装饰逻辑，避免两处字段与判定漂移）。
     *
     * @param violation      违规
     * @param appeal         该违规的申诉，可为 null
     * @param subjectUserId  以谁的视角计算可动作；null 表示只看不判
     * @return 视图
     */
    @Transactional(readOnly = true)
    public AppealDTO.ViolationVO view(Violation violation, Appeal appeal, Long subjectUserId) {
        return toVO(violation, appeal, subjectUserId, LocalDateTime.now());
    }

    private AppealDTO.ViolationVO toVO(Violation violation, Appeal appeal, Long subjectUserId, LocalDateTime now) {
        Integer remainHours = remainHours(violation, appeal, now);
        String disabled = disabledReason(violation, appeal, now);
        String revokeDisabled = revokeDisabledReason(violation, appeal);
        return AppealDTO.ViolationVO.builder()
                .violationId(violation.getId())
                .userId(violation.getUserId())
                .reservationId(violation.getReservationId())
                .type(violation.getType().name())
                .typeText(violation.getType().getText())
                .scoreDelta(violation.getScoreDelta())
                .status(violation.getStatus().name())
                .statusText(violation.getStatus().getText())
                .createdBy(violation.getCreatedBy())
                .createTime(BizConstants.formatDateTime(violation.getCreateTime()))
                .revokeTime(BizConstants.formatDateTime(violation.getRevokeTime()))
                .revokeReason(violation.getRevokeReason())
                .evidence(violation.getEvidence())
                .evidenceItems(evidenceView(violation.getEvidence()))
                .appealId(appeal == null ? null : appeal.getId())
                .appealStatus(appeal == null ? null : appeal.getStatus().name())
                .appealStatusText(appeal == null ? null : appeal.getStatus().getText())
                .appealReply(appeal == null ? null : appeal.getReply())
                .appealHandleTime(appeal == null ? null : BizConstants.formatDateTime(appeal.getHandleTime()))
                .canAppeal(disabled == null)
                .appealDisabledReason(disabled)
                .appealRemainHours(remainHours)
                .canEscalate(appeal != null && appeal.getStatus().escalatable()
                        && (subjectUserId == null || subjectUserId.equals(appeal.getUserId())))
                .canRevoke(revokeDisabled == null)
                .revokeDisabledReason(revokeDisabled)
                .build();
    }

    /**
     * 「这条能不能从馆员撤销入口处理」—— 与 {@link #revoke} 的入口校验同一套规则。
     *
     * @return null 表示可以撤销
     */
    private String revokeDisabledReason(Violation violation, Appeal appeal) {
        if (violation.getStatus() == ViolationStatus.REVOKED) {
            return "该违规已撤销，无需重复操作";
        }
        if (appeal != null && appeal.getStatus().judgeable()) {
            return "该违规正在申诉流程中，请到申诉队列裁定";
        }
        return null;
    }

    /**
     * 申诉窗口剩余小时数。
     *
     * @return 剩余额（向下取整，最小 0）；已定案 / 已撤销 / 已超窗返回 null
     */
    private Integer remainHours(Violation violation, Appeal appeal, LocalDateTime now) {
        if (appeal != null || violation.getStatus() == ViolationStatus.REVOKED) {
            return null;
        }
        long left = Duration.between(now, deadline(violation)).toHours();
        return left < 0 ? null : (int) left;
    }

    /**
     * 为什么现在不能申诉（返回 null 表示可以）。
     *
     * <p>判定顺序刻意与 {@code AppealService.submit} 一致：列表页显示的「不可申诉」理由
     * 必须和真按提交时拿到的错误同源，否则用户会看到「可以申诉」却被打回。
     */
    private String disabledReason(Violation violation, Appeal appeal, LocalDateTime now) {
        if (violation.getStatus() == ViolationStatus.REVOKED) {
            return "该违规已撤销，无需申诉";
        }
        if (appeal != null) {
            return switch (appeal.getStatus()) {
                case PENDING, ESCALATED -> "申诉正在处理中";
                case APPROVED, FINAL_JUDGED -> "申诉已定案";
                case REJECTED -> "已被驳回，请走「升级终审」";
            };
        }
        if (deadline(violation).isBefore(now)) {
            return "已超过 " + BizConstants.APPEAL_WINDOW_HOURS + " 小时申诉时限，请到馆员处申请撤销";
        }
        return null;
    }

    private LocalDateTime deadline(Violation violation) {
        return violation.getCreateTime().plusHours(BizConstants.APPEAL_WINDOW_HOURS);
    }

    private Violation requireViolation(Long violationId) {
        return violationRepository.findById(violationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "违规记录不存在"));
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

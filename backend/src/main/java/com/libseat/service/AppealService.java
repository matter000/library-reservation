package com.libseat.service;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 申诉闭环（DESIGN §6.3）：提交 → 馆员复核 → 学生升级 → 系统管理员终审。
 *
 * <p>★ 三件事必须在同一个事务里：违规状态推进、分数回补、申诉状态落定。
 * 少了任何一步都会留下「申诉成立但分数没回」或「分数回了但违规还挂在账上」的半案，
 * 而这两种半案在对账接口上表现得不一样，排查成本极高。
 *
 * <p>★ 回补只走 {@link CreditService#restore}（反向记一笔 {@code RESTORE} 流水），
 * 绝不改写当年的 {@code PENALTY} 流水：历史账本一旦被改写，「这条违规当时扣了多少」
 * 就再也问不出来了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealService {

    private final AppealRepository appealRepository;

    private final ViolationRepository violationRepository;

    private final ViolationService violationService;

    private final CreditService creditService;

    private final AuditService auditService;

    private final NoticeService noticeService;

    /**
     * 学生提交申诉（S12）。
     *
     * @param violationId 违规 ID
     * @param userId      申诉人（必须是违规本人）
     * @param role        申诉人角色（只用于算视图里的可动作）
     * @param reason      事由
     * @return 申诉视图
     */
    @Transactional
    public AppealDTO.AppealVO submit(Long violationId, Long userId, UserRole role, String reason) {
        // 不是自己的违规按「不存在」处理：与暂离那套一致，不借这个入口探测他人违规是否存在
        Violation violation = violationRepository.findById(violationId)
                .filter(v -> v.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "违规记录不存在"));
        if (violation.getStatus() == ViolationStatus.REVOKED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该违规已撤销，无需申诉");
        }
        if (appealRepository.findByViolationId(violationId).isPresent()) {
            // 「已存在」与「超时限」共用 4108（DESIGN §12.4），但文案要说清是哪一种：
            // 被驳回的学生如果只看到「申诉已存在」，会以为刷新一下就能重提
            throw new BusinessException(ErrorCode.APPEAL_NOT_ALLOWED,
                    "该违规已有申诉记录，请查看裁定结果或使用「升级终审」");
        }
        LocalDateTime deadline = violation.getCreateTime().plusHours(BizConstants.APPEAL_WINDOW_HOURS);
        if (deadline.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.APPEAL_NOT_ALLOWED,
                    "已超过 " + BizConstants.APPEAL_WINDOW_HOURS + " 小时申诉时限（截止 "
                            + BizConstants.formatDateTime(deadline) + "），请到馆员处申请撤销");
        }

        Appeal appeal = Appeal.builder()
                .violationId(violationId)
                .userId(userId)
                .reason(reason.trim())
                .status(AppealStatus.PENDING)
                .createTime(LocalDateTime.now())
                .build();
        try {
            appeal = appealRepository.saveAndFlush(appeal);
        } catch (DataIntegrityViolationException e) {
            // ★ 上面的「已存在」检查挡不住并发双击（两个事务都先读到空），
            //   真正兜住的是 uk_appeal_violation；这里把它翻译回 4108 而不是 500。
            throw new BusinessException(ErrorCode.APPEAL_NOT_ALLOWED, "该违规已有申诉记录，请勿重复提交");
        }

        violation.setStatus(ViolationStatus.APPEALING);
        violationRepository.save(violation);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("violationId", violationId);
        detail.put("appealId", appeal.getId());
        detail.put("userId", userId);
        detail.put("reason", appeal.getReason());
        auditService.record(AuditService.ACTION_APPEAL_SUBMIT, "APPEAL", appeal.getId(), detail);
        log.info("申诉已提交：appealId={}, violationId={}, userId={}", appeal.getId(), violationId, userId);
        return toVO(appeal, violation, userId, role);
    }

    /**
     * 学生对驳回不服，升级至系统管理员终审（S13）。
     *
     * @param violationId 违规 ID
     * @param userId      申诉人（必须是违规本人）
     * @param role        申诉人角色
     * @return 申诉视图
     */
    @Transactional
    public AppealDTO.AppealVO escalate(Long violationId, Long userId, UserRole role) {
        Appeal appeal = appealRepository.findByViolationId(violationId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "申诉记录不存在"));
        if (!appeal.getStatus().escalatable()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "只有「" + AppealStatus.REJECTED.getText() + "」的申诉可以升级终审，当前状态："
                            + appeal.getStatus().getText());
        }
        appeal.setStatus(AppealStatus.ESCALATED);
        Appeal saved = appealRepository.save(appeal);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("appealId", saved.getId());
        detail.put("violationId", violationId);
        detail.put("from", AppealStatus.REJECTED.name());
        detail.put("to", AppealStatus.ESCALATED.name());
        auditService.record(AuditService.ACTION_APPEAL_ESCALATE, "APPEAL", saved.getId(), detail);
        log.info("申诉已升级终审：appealId={}, userId={}", saved.getId(), userId);
        return toVO(saved, requireViolation(saved.getViolationId()), userId, role);
    }

    /**
     * 裁定申诉（F9）：馆员复核 {@code PENDING}，系统管理员终审 {@code ESCALATED}。
     *
     * @param appealId    申诉 ID
     * @param approve     是否成立
     * @param reply       裁定说明（驳回必填）
     * @param handlerId   裁定人
     * @param handlerRole 裁定人角色
     * @return 申诉视图
     */
    @Transactional
    public AppealDTO.AppealVO judge(Long appealId, boolean approve, String reply,
                                    Long handlerId, UserRole handlerRole) {
        Appeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "申诉记录不存在"));
        if (!appeal.getStatus().judgeable()) {
            // ★ 已经裁过的（不管是谁裁的、含终审）都不能再裁一遍：否则同一笔扣分可能被回补两次
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该申诉已裁定为「" + appeal.getStatus().getText() + "」，不能重复裁定");
        }
        if (appeal.getStatus() == AppealStatus.ESCALATED && handlerRole != UserRole.SYS_ADMIN) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE,
                    "升级后的申诉只能由系统管理员终审，馆员无权限");
        }
        if (!approve && !StringUtils.hasText(reply)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "驳回必须写明理由，否则学生不知道缺什么");
        }

        Violation violation = requireViolation(appeal.getViolationId());
        if (violation.getStatus() == ViolationStatus.REVOKED) {
            // 违规已被馆员从 F8 撤销过：分数已经回补，这里绝不能二次回补
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该违规已被撤销，申诉无需再裁定");
        }

        LocalDateTime now = LocalDateTime.now();
        int restored = 0;
        if (approve) {
            restored = -violation.getScoreDelta();
            creditService.restore(violation.getUserId(), restored, appeal.getId(),
                    "申诉成立回补：" + violation.getType().getText());
            violation.setStatus(ViolationStatus.REVOKED);
            violation.setRevokeTime(now);
            violation.setRevokeReason(cut("申诉成立：" + (StringUtils.hasText(reply) ? reply.trim()
                    : violation.getType().getText()), 200));
        } else {
            violation.setStatus(ViolationStatus.ACTIVE);
        }
        violationRepository.save(violation);

        // 终审件无论成立或驳回都落到 FINAL_JUDGED：它是这条链上唯一能定案的终态
        boolean finalJudgement = appeal.getStatus() == AppealStatus.ESCALATED;
        appeal.setStatus(finalJudgement ? AppealStatus.FINAL_JUDGED
                : (approve ? AppealStatus.APPROVED : AppealStatus.REJECTED));
        appeal.setHandlerId(handlerId);
        appeal.setHandlerRole(handlerRole == null ? null : handlerRole.name());
        appeal.setReply(StringUtils.hasText(reply) ? reply.trim() : null);
        appeal.setHandleTime(now);
        Appeal saved = appealRepository.save(appeal);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("appealId", saved.getId());
        detail.put("violationId", violation.getId());
        detail.put("userId", violation.getUserId());
        detail.put("approve", approve);
        detail.put("finalJudgement", finalJudgement);
        detail.put("restoredScore", restored);
        detail.put("reply", saved.getReply());
        auditService.record(AuditService.ACTION_APPEAL_JUDGE, "APPEAL", saved.getId(), detail);
        // 裁定结果即时告知：成立要说清回了多少分，驳回要把驳回理由带进正文 ——
        // 学生点开通知时不该只看到「申诉已裁定」四个字。
        noticeService.push(violation.getUserId(), com.libseat.enums.NoticeType.APPEAL_RESULT,
                "申诉" + (approve ? "成立" : "驳回"),
                approve
                        ? "违规（" + violation.getType().getText() + "）的申诉已成立，回补 " + restored
                        + " 分，信用分已恢复。"
                        : "违规（" + violation.getType().getText() + "）的申诉被驳回："
                        + (StringUtils.hasText(reply) ? reply.trim() : "维持原裁定。")
                        + (finalJudgement ? "（本件已终审）" : "如仍有异议可在「信用与违规」升级终审。"),
                NoticeService.REF_APPEAL, saved.getId(), null);
        log.info("申诉已裁定：appealId={}, approve={}, 状态={}, 回补={}, 裁定人角色={}",
                saved.getId(), approve, saved.getStatus(), restored, saved.getHandlerRole());
        return toVO(saved, violation, violation.getUserId(), handlerRole);
    }

    /**
     * 馆员 / 管理面的申诉队列（F9）。
     *
     * @param status     状态过滤；null 表示全部
     * @param callerRole 调用方角色，用于算「这条我能不能裁」
     * @return 队列（旧的在前）
     */
    @Transactional(readOnly = true)
    public List<AppealDTO.AppealVO> queue(AppealStatus status, UserRole callerRole) {
        List<Appeal> source = (status == null)
                ? appealRepository.findAllByOrderByIdAsc()
                : appealRepository.findByStatusOrderByIdAsc(status);
        List<AppealDTO.AppealVO> result = new ArrayList<>();
        for (Appeal appeal : source) {
            Violation violation = violationRepository.findById(appeal.getViolationId()).orElse(null);
            result.add(toVO(appeal, violation, appeal.getUserId(), callerRole));
        }
        return result;
    }

    private Violation requireViolation(Long violationId) {
        return violationRepository.findById(violationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "违规记录不存在"));
    }

    /**
     * 组装申诉视图。
     *
     * @param appeal        申诉
     * @param violation     被申诉的违规（脏数据时可为 null，此时只回申诉本身）
     * @param subjectUserId 违规明细里的可动作按谁的视角算（申诉人）
     * @param callerRole    调用方角色（决定 canJudge）
     * @return 视图
     */
    private AppealDTO.AppealVO toVO(Appeal appeal, Violation violation, Long subjectUserId, UserRole callerRole) {
        String judgeDisabled = judgeDisabledReason(appeal, callerRole);
        return AppealDTO.AppealVO.builder()
                .appealId(appeal.getId())
                .violationId(appeal.getViolationId())
                .userId(appeal.getUserId())
                .reason(appeal.getReason())
                .status(appeal.getStatus().name())
                .statusText(appeal.getStatus().getText())
                .handlerRole(appeal.getHandlerRole())
                .reply(appeal.getReply())
                .createTime(BizConstants.formatDateTime(appeal.getCreateTime()))
                .handleTime(BizConstants.formatDateTime(appeal.getHandleTime()))
                .canJudge(judgeDisabled == null)
                .judgeDisabledReason(judgeDisabled)
                .violation(violation == null ? null
                        : violationService.view(violation, appeal, subjectUserId))
                .build();
    }

    /**
     * 「这条我能不能裁」—— 与 {@link #judge} 的入口校验同一套规则，避免队列里显示可裁、
     * 点下去却拿到 4031/4093。
     *
     * @return null 表示可以裁定
     */
    private String judgeDisabledReason(Appeal appeal, UserRole callerRole) {
        if (callerRole != UserRole.LIBRARIAN && callerRole != UserRole.SYS_ADMIN) {
            return "仅馆员与系统管理员可裁定申诉";
        }
        if (!appeal.getStatus().judgeable()) {
            return "已裁定为「" + appeal.getStatus().getText() + "」";
        }
        if (appeal.getStatus() == AppealStatus.ESCALATED && callerRole != UserRole.SYS_ADMIN) {
            return "升级件只能由系统管理员终审";
        }
        return null;
    }

    private String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

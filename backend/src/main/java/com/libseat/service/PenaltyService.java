package com.libseat.service;

import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.Penalty;
import com.libseat.enums.PenaltyStatus;
import com.libseat.enums.PenaltyType;
import com.libseat.repository.PenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 处罚阶梯（DESIGN §6.2）。
 *
 * <p>★ 阶梯是「当前分数的函数」，不是「历史违规的累计」：每次分数变动后调用
 * {@link #syncLadder} 把 ACTIVE 处罚收敛到分数所处的那一档。
 * 于是回分自动解罚（不必等定时任务），重复违规也只是延长同一档的 {@code end_time}，
 * 天然满足「同 user+type 只允许一条 ACTIVE」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PenaltyService {

    private final PenaltyRepository penaltyRepository;

    private final RuleConfigService ruleConfigService;

    /**
     * 分数所处的处罚档位。
     *
     * @param score 当前信用分
     * @return 应生效的处罚；分数在 90~100 时为 null
     */
    public PenaltyType typeForScore(int score) {
        if (score < ruleConfigService.creditSemesterUnder()) {
            return PenaltyType.SEMESTER_BAN;
        }
        if (score < ruleConfigService.creditBan7dUnder()) {
            return PenaltyType.BOOK_BAN_7D;
        }
        if (score < ruleConfigService.creditBan3dUnder()) {
            return PenaltyType.BOOK_BAN_3D;
        }
        if (score < ruleConfigService.creditPeakBanUnder()) {
            return PenaltyType.PEAK_BAN;
        }
        return null;
    }

    /**
     * 把 ACTIVE 处罚收敛到当前档位：降档则解除旧的、升档则新建或延长。
     *
     * @param userId 用户 ID
     * @param score  变动后的信用分
     * @param reason 触发说明（写进处罚理由列，管理端要能看懂为什么被禁）
     * @param now    参照时刻
     */
    @Transactional
    public void syncLadder(Long userId, int score, String reason, LocalDateTime now) {
        PenaltyType desired = typeForScore(score);
        for (Penalty existing : penaltyRepository.findByUserIdAndStatusOrderByIdDesc(userId, PenaltyStatus.ACTIVE)) {
            if (existing.getType() == desired) {
                continue;
            }
            existing.setStatus(PenaltyStatus.RELEASED);
            existing.setEndTime(now);
            existing.setReleaseNote("信用分回升至 " + score + "，阶梯自动解除");
            penaltyRepository.save(existing);
        }
        if (desired == null) {
            return;
        }
        Penalty active = penaltyRepository
                .findFirstByUserIdAndTypeAndStatus(userId, desired, PenaltyStatus.ACTIVE)
                .orElse(null);
        if (active == null) {
            penaltyRepository.save(Penalty.builder()
                    .userId(userId)
                    .type(desired)
                    .startTime(now)
                    .endTime(endTimeOf(desired, now))
                    .status(PenaltyStatus.ACTIVE)
                    .reason(reason)
                    .build());
            log.info("信用阶梯触发处罚：userId={}, type={}, score={}", userId, desired, score);
            return;
        }
        // 同类重复违规：只延长，不再插一条（否则「当前是否受限」要看多条记录才能判定）
        LocalDateTime extended = endTimeOf(desired, now);
        if (extended != null && (active.getEndTime() == null || extended.isAfter(active.getEndTime()))) {
            active.setEndTime(extended);
            active.setReason(reason);
            penaltyRepository.save(active);
        }
    }

    /**
     * 某人此刻生效的处罚（已按 {@code end_time} 判活）。
     *
     * @param userId 用户 ID
     * @param now    参照时刻
     * @return 生效中的处罚
     */
    @Transactional(readOnly = true)
    public List<Penalty> activePenalties(Long userId, LocalDateTime now) {
        return penaltyRepository.findByUserIdAndStatusOrderByIdDesc(userId, PenaltyStatus.ACTIVE).stream()
                .filter(penalty -> penalty.activeAt(now))
                .toList();
    }

    /**
     * 是否被某类处罚限制。
     *
     * @param userId 用户 ID
     * @param type   类型
     * @param now    参照时刻
     * @return 是否生效
     */
    @Transactional(readOnly = true)
    public boolean hasActive(Long userId, PenaltyType type, LocalDateTime now) {
        return activePenalties(userId, now).stream().anyMatch(penalty -> penalty.getType() == type);
    }

    /**
     * 人工解除。
     *
     * <p>★「暂停至学期末」只能由系统管理员解除（§6.2 表）：馆员入口传 {@code allowSemester=false}，
     * 遇到学期禁直接 4031，而不是悄悄放开。
     *
     * @param penaltyId     处罚 ID
     * @param operatorId    操作人
     * @param note          说明
     * @param allowSemester 是否允许解除学期禁（系统管理员入口传 true）
     * @return 解除后的处罚
     */
    @Transactional
    public Penalty release(Long penaltyId, Long operatorId, String note, boolean allowSemester) {
        Penalty penalty = penaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "处罚记录不存在"));
        if (penalty.getStatus() != PenaltyStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该处罚已解除，无需重复操作");
        }
        if (penalty.getType() == PenaltyType.SEMESTER_BAN && !allowSemester) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE, "暂停至学期末只能由系统管理员解除");
        }
        penalty.setStatus(PenaltyStatus.RELEASED);
        penalty.setEndTime(LocalDateTime.now());
        penalty.setReleasedBy(operatorId);
        penalty.setReleaseNote(note);
        return penaltyRepository.save(penalty);
    }

    private LocalDateTime endTimeOf(PenaltyType type, LocalDateTime now) {
        return switch (type) {
            case BOOK_BAN_3D -> now.plusDays(ruleConfigService.creditBan3dDays());
            case BOOK_BAN_7D -> now.plusDays(ruleConfigService.creditBan7dDays());
            // PEAK_BAN / SEMESTER_BAN 没有固定终点：前者随分数回升解除，后者只能系统管理员解除
            case PEAK_BAN, SEMESTER_BAN -> null;
        };
    }
}

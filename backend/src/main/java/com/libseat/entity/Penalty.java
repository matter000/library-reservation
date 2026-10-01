package com.libseat.entity;

import com.libseat.enums.PenaltyStatus;
import com.libseat.enums.PenaltyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 处罚（t_penalty，DESIGN §6.2）。
 *
 * <p>★ 幂等口径：同 {@code user_id + type} 只允许一条 ACTIVE，重复违规时延长
 * {@code end_time} 而不是再插一条 —— 否则「当前是否受限」要去看多条记录才能判定。
 *
 * <p>判活一律用「ACTIVE 且（end_time 为空或尚未到期）」，不把到期状态交给定时任务翻转，
 * 这样即使扫描没跑，也不会误禁。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_penalty")
public class Penalty {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被处罚用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 处罚类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private PenaltyType type;

    /** 生效时刻。 */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** 结束时刻；null 表示需加分或人工解除。 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PenaltyStatus status = PenaltyStatus.ACTIVE;

    /** 触发理由（含分数与规则名）。 */
    @Column(name = "reason", length = 100)
    private String reason;

    /** 解除操作人。 */
    @Column(name = "released_by")
    private Long releasedBy;

    /** 解除说明。 */
    @Column(name = "release_note", length = 200)
    private String releaseNote;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (status == null) {
            status = PenaltyStatus.ACTIVE;
        }
    }

    /**
     * 此刻是否仍在生效。
     *
     * @param now 参照时刻
     * @return ACTIVE 且未过期
     */
    public boolean activeAt(LocalDateTime now) {
        if (status != PenaltyStatus.ACTIVE || startTime != null && startTime.isAfter(now)) {
            return false;
        }
        return endTime == null || endTime.isAfter(now);
    }
}

package com.libseat.entity;

import com.libseat.enums.ViolationStatus;
import com.libseat.enums.ViolationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 违规记录（t_violation，DESIGN §6.1）。
 *
 * <p>★ {@code evidence} 是 NOT NULL 的 JSON：PRD §12-22「无证据不得裁定违规」。
 * 证据由触发方组装（规则名 + 关键时间戳 + 操作人 + 巡查任务号），
 * 申诉裁定页要能把这段 JSON 渲染成人能读的话，所以字段名即契约，不随意改名。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_violation")
public class Violation {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 违规主体。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 关联预约；现场占座这类没有预约的情形为 null。 */
    @Column(name = "reservation_id")
    private Long reservationId;

    /** 类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private ViolationType type;

    /** 本次扣分（负数）。 */
    @Column(name = "score_delta", nullable = false)
    private Integer scoreDelta;

    /** ★ 证据 JSON，必填。 */
    @Lob
    @Column(name = "evidence", nullable = false)
    private String evidence;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ViolationStatus status = ViolationStatus.ACTIVE;

    /** 来源：SYSTEM（规则自动）/ LIBRARIAN（巡查裁定）。 */
    @Column(name = "created_by", nullable = false, length = 20)
    private String createdBy;

    /** 裁定人（created_by=LIBRARIAN 时填）。 */
    @Column(name = "creator_id")
    private Long creatorId;

    /** 撤销时刻。 */
    @Column(name = "revoke_time")
    private LocalDateTime revokeTime;

    /** 撤销理由。 */
    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (status == null) {
            status = ViolationStatus.ACTIVE;
        }
    }
}

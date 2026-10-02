package com.libseat.entity;

import com.libseat.enums.AppealStatus;
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
 * 违规申诉（t_appeal，DESIGN §6.1 / §6.3）。
 *
 * <p>一条违规只能有一条申诉：{@code uk_appeal_violation} 是硬约束，
 * 驳回后的救济走 {@code REJECTED → ESCALATED} 状态迁移，而不是再插一条新的申诉，
 * 这样「这条违规现在的结论是什么」永远只需要看一条记录。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_appeal")
public class Appeal {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被申诉的违规（唯一）。 */
    @Column(name = "violation_id", nullable = false)
    private Long violationId;

    /** 申诉人（冗余一份，学生面「我的申诉」不必回头 join 违规）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 学生陈述的事由。 */
    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppealStatus status = AppealStatus.PENDING;

    /** 裁定人。 */
    @Column(name = "handler_id")
    private Long handlerId;

    /** 裁定人角色（LIBRARIAN=复核，SYS_ADMIN=终审）。 */
    @Column(name = "handler_role", length = 20)
    private String handlerRole;

    /** 裁定说明（驳回时必填）。 */
    @Column(name = "reply", length = 500)
    private String reply;

    /** 提交时刻。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 裁定时刻。 */
    @Column(name = "handle_time")
    private LocalDateTime handleTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (status == null) {
            status = AppealStatus.PENDING;
        }
    }
}

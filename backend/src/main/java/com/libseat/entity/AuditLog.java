package com.libseat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作审计日志：谁在何时对什么做了什么。
 *
 * <p>强制释放、规则变更、空间删除、违规撤销等治理动作必须留痕，
 * 否则申诉与纠纷无法追溯（PRD-V2 §10.3、§12-22）。
 */
@Entity
@Table(name = "t_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人 ID；系统自动操作时为 null。 */
    @Column(name = "actor_id")
    private Long actorId;

    /** 操作人角色。 */
    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", length = 30)
    private String targetId;

    /** 明细 JSON（旧值/新值/原因等）。 */
    @Column(name = "detail", columnDefinition = "CLOB")
    private String detail;

    @Column(name = "create_time", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();
}

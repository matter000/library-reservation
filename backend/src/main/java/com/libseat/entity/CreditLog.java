package com.libseat.entity;

import com.libseat.enums.CreditLogKind;
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
 * 信用流水（t_credit_log）：只追加、不改写、不删除的账本。
 *
 * <p>不变式 {@code sum(delta) == t_user.credit_score}（对账接口即按此断言），
 * 所以初始分也必须是一笔 {@link CreditLogKind#INIT} 流水，而不是「表的默认值」。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_credit_log")
public class CreditLog {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 流水类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private CreditLogKind kind;

    /** 变化量（正负皆可）。 */
    @Column(name = "delta", nullable = false)
    private Integer delta;

    /** ★ 记账后的余额快照，必填：让任意一条流水都能独立解释余额。 */
    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    /** 关联对象类型，如 VIOLATION / RESERVATION / APPEAL。 */
    @Column(name = "ref_type", length = 20)
    private String refType;

    /** 关联对象 ID。 */
    @Column(name = "ref_id")
    private Long refId;

    /** 可读理由。 */
    @Column(name = "reason", length = 100)
    private String reason;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
    }
}

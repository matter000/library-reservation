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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 规则配置项（后台可配，不发版）。
 *
 * <p>{@code scopeId} 用空串表示 GLOBAL 作用域而非 NULL —— NULL 在唯一索引中互不相等，
 * 用 NULL 会让「同 key 重复插入」逃过 {@code uk_rule} 约束。
 */
@Entity
@Table(name = "t_rule_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GLOBAL / ZONE / DAY_TYPE。 */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /** 作用域对象 ID；GLOBAL 为空串。 */
    @Column(name = "scope_id", nullable = false, length = 30)
    private String scopeId;

    @Column(name = "rkey", nullable = false, length = 50)
    private String rkey;

    @Column(name = "rvalue", nullable = false, length = 200)
    private String rvalue;

    /** INT / BOOL / TIME / STRING / INT_LIST。 */
    @Column(name = "value_type", nullable = false, length = 10)
    private String valueType;

    /** OPEN / BOOK / LEAVE / CREDIT / PEAK / PATROL。 */
    @Column(name = "rgroup", nullable = false, length = 30)
    private String rgroup;

    @Column(name = "editable", nullable = false)
    private Boolean editable;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "create_time", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createTime = LocalDateTime.now();

    @UpdateTimestamp
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}

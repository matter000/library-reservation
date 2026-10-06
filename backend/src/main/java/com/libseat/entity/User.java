package com.libseat.entity;

import com.libseat.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户实体（t_user）：普通用户与管理员共用一张表。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_user")
public class User {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录账号，唯一。 */
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    /** BCrypt 密文。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    /** 真实姓名。 */
    @Column(name = "real_name", nullable = false, length = 50)
    private String realName;

    /**
     * 学号 / 教工号（PRD §12-24：馆员看姓名时也要能确认"是不是这个人"）。
     *
     * <p>★ 允许为空：管理员或外部账号可能没有校园号。唯一索引 {@code uk_user_student_no}
     * 在 H2/MySQL 下都不把多行 NULL 判成冲突，所以"很多人没有学号"不会撞约束。
     */
    @Column(name = "student_no", length = 30)
    private String studentNo;

    /** 手机号（管理员端脱敏展示）。 */
    @Column(name = "phone", length = 20)
    private String phone;

    /** 角色。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.STUDENT;

    /** 超时未签到次数。 */
    @Builder.Default
    @Column(name = "no_show_count", nullable = false)
    private Integer noShowCount = 0;

    /**
     * 信用分（t_credit_log 的余额快照）。
     *
     * <p>★ 只允许 {@code CreditService} 改它，且每次改动必须同时写一笔流水，
     * 否则「sum(delta) == credit_score」的对账不变式就破了。
     */
    @Builder.Default
    @Column(name = "credit_score", nullable = false)
    private Integer creditScore = 100;

    /**
     * 连续「主动结束使用」次数（DESIGN §5.4）。
     *
     * <p>迁移 {@code V2__role.sql} 已建此列，S3 信用分上线前只累计不加分；
     * 每满 5 次由信用服务给 +5 并清零。
     */
    @Builder.Default
    @Column(name = "active_finish_streak", nullable = false)
    private Integer activeFinishStreak = 0;

    /** 账号状态：ACTIVE / DISABLED。 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
        }
        if (updateTime == null) {
            updateTime = now;
        }
        if (role == null) {
            role = UserRole.STUDENT;
        }
        if (noShowCount == null) {
            noShowCount = 0;
        }
        if (activeFinishStreak == null) {
            activeFinishStreak = 0;
        }
        if (creditScore == null) {
            creditScore = 100;
        }
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

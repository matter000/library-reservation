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
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

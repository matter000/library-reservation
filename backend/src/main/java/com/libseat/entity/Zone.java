package com.libseat.entity;

import com.libseat.enums.DiscussLevel;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ZoneType;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 分区实体（t_zone）。
 *
 * <p><b>设计红线</b>：分区是规则的<b>唯一</b>载体（时长上限、暂离、组队、可见/可约角色），
 * 不给单个座位配规则；座位通过 {@code zone_id} 继承。
 *
 * <p>{@code visibleRoles} / {@code eligibleRoles} 存角色枚举名的 CSV —— 角色集合小而稳定，
 * 拆关联表收益为负；写入时必须逐段校验为合法 {@link com.libseat.enums.UserRole}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_zone",
        uniqueConstraints = @UniqueConstraint(name = "uk_zone_floor_code", columnNames = {"floor_id", "code"})
)
public class Zone {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属楼层。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 分区名称。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 楼层内唯一编码，如 1F-SILENT。 */
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    /** 分区类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, length = 30)
    private ZoneType zoneType;

    /** 开放状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FloorStatus status = FloorStatus.OPEN;

    /** 单次时长上限（分钟）。 */
    @Builder.Default
    @Column(name = "max_duration_min", nullable = false)
    private Integer maxDurationMin = 240;

    /** 单日累计上限（分钟）；null 表示不设分区级上限。 */
    @Column(name = "daily_cap_min")
    private Integer dailyCapMin;

    /** 可提前预约天数（含当天）。 */
    @Builder.Default
    @Column(name = "advance_days", nullable = false)
    private Integer advanceDays = 3;

    /** 讨论音量等级。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "allow_discuss", nullable = false, length = 10)
    private DiscussLevel allowDiscuss = DiscussLevel.NO;

    /** 是否允许暂离。 */
    @Builder.Default
    @Column(name = "allow_leave", nullable = false)
    private Boolean allowLeave = Boolean.TRUE;

    /** 本区单次暂离封顶（分钟）。 */
    @Builder.Default
    @Column(name = "leave_max_min", nullable = false)
    private Integer leaveMaxMin = 60;

    /** 本区每日暂离次数上限。 */
    @Builder.Default
    @Column(name = "leave_daily_limit", nullable = false)
    private Integer leaveDailyLimit = 3;

    /** 是否支持组队预约。 */
    @Builder.Default
    @Column(name = "allow_group", nullable = false)
    private Boolean allowGroup = Boolean.FALSE;

    /** 是否仅接受整组预约（研讨协作区）。 */
    @Builder.Default
    @Column(name = "group_required", nullable = false)
    private Boolean groupRequired = Boolean.FALSE;

    /** 可见角色 CSV。 */
    @Builder.Default
    @Column(name = "visible_roles", nullable = false, length = 100)
    private String visibleRoles = "STUDENT,TEACHER,LIBRARIAN,SYS_ADMIN";

    /** 可预约角色 CSV。 */
    @Builder.Default
    @Column(name = "eligible_roles", nullable = false, length = 100)
    private String eligibleRoles = "STUDENT,TEACHER,LIBRARIAN,SYS_ADMIN";

    /** 排序号。 */
    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 备注。 */
    @Column(name = "remark", length = 200)
    private String remark;

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
        if (status == null) {
            status = FloorStatus.OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

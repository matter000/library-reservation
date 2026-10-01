package com.libseat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 高峰窗口（t_peak_window）—— 在指定日型的某段时段内压缩可约时长。
 *
 * <p>作用域三选一：GLOBAL（全馆）/ FLOOR（楼层）/ ZONE（分区）；
 * {@code dayTypes} 存日型 CSV，命中判断由 OpenRuleService 统一负责，
 * 各处不得自行解析 CSV（B2 同源约定的延伸）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_peak_window")
public class PeakWindow {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 作用域：GLOBAL / FLOOR / ZONE。 */
    @Builder.Default
    @Column(name = "scope", nullable = false, length = 20)
    private String scope = "GLOBAL";

    /** 楼层 ID（scope=FLOOR 时必填）。 */
    @Column(name = "floor_id")
    private Long floorId;

    /** 分区 ID（scope=ZONE 时必填）。 */
    @Column(name = "zone_id")
    private Long zoneId;

    /** 名称。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 命中的日型 CSV。 */
    @Builder.Default
    @Column(name = "day_types", nullable = false, length = 60)
    private String dayTypes = "WORKDAY";

    /** 起始时段序号（含）。 */
    @Column(name = "start_slot", nullable = false)
    private Integer startSlot;

    /** 结束时段的开区间边界。 */
    @Column(name = "end_slot", nullable = false)
    private Integer endSlot;

    /** 高峰期间单次时长上限（分钟）。 */
    @Column(name = "max_duration_min")
    private Integer maxDurationMin;

    /** 高峰时段提前开放的时段格（可空）。 */
    @Column(name = "advance_open_slot")
    private Integer advanceOpenSlot;

    /** 是否启用。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

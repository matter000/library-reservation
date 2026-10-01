package com.libseat.entity;

import com.libseat.enums.DayType;
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
 * 开放档案（t_open_profile）—— 按日型定义开闭馆边界。
 *
 * <p>边界存<b>时段序号</b>而非时间字符串：与 t_reservation_slot 同一口径，
 * 避免两处各自换算分钟导致的偏差。{@code openEnd} 是开区间边界。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_open_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_profile_day_type", columnNames = "day_type")
)
public class OpenProfile {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 日型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    /** 档案名称。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 起始时段序号（含）。 */
    @Column(name = "open_start", nullable = false)
    private Integer openStart;

    /** 结束时段的开区间边界。 */
    @Column(name = "open_end", nullable = false)
    private Integer openEnd;

    /** 该日型是否整体闭馆。 */
    @Builder.Default
    @Column(name = "closed", nullable = false)
    private Boolean closed = Boolean.FALSE;

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

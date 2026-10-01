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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开放日历（t_open_calendar）—— 针对具体日期的覆盖项。
 *
 * <p>解析优先级：日历覆盖 → 日型档案 → 全局默认。
 * 可空字段表示「该项沿用档案值」，只有 {@code closed} 是显式布尔（临时闭馆必须写死）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "t_open_calendar",
        uniqueConstraints = @UniqueConstraint(name = "uk_calendar_date", columnNames = "open_date")
)
public class OpenCalendar {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 具体日期。 */
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /** 覆盖日型，空表示沿用星期推断。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", length = 20)
    private DayType dayType;

    /** 覆盖起始时段序号。 */
    @Column(name = "open_start")
    private Integer openStart;

    /** 覆盖结束时段的开区间边界。 */
    @Column(name = "open_end")
    private Integer openEnd;

    /** 临时闭馆：当日不可预约，且平面图全灰。 */
    @Builder.Default
    @Column(name = "closed", nullable = false)
    private Boolean closed = Boolean.FALSE;

    /** 备注（如「国庆假期」）。 */
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

package com.libseat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 暂离原因字典（t_leave_reason，DESIGN §5.1）。
 *
 * <p>主键是业务码而非自增 id：原因集合是配置而非事件，让 {@code t_leave_record.reason_code}
 * 直接引用可读的码，比多一层 id 关联更好核对。新增原因走迁移脚本插行，与管理面改值区分开。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_leave_reason")
public class LeaveReason {

    /** 原因码，如 MEAL。 */
    @Id
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    /** 展示名称。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 单次最长暂离分钟数。 */
    @Column(name = "max_min", nullable = false)
    private Integer maxMin;

    /** 该原因每日次数上限（与分区上限取较小值）。 */
    @Column(name = "daily_limit", nullable = false)
    private Integer dailyLimit = 3;

    /** 是否必须填写事由。 */
    @Column(name = "need_note", nullable = false)
    private Boolean needNote = Boolean.FALSE;

    /** 是否启用。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 排序。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

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
        if (dailyLimit == null) {
            dailyLimit = 3;
        }
        if (needNote == null) {
            needNote = Boolean.FALSE;
        }
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

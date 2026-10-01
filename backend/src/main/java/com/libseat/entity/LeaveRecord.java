package com.libseat.entity;

import com.libseat.enums.LeaveStatus;
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
 * 暂离记录（t_leave_record，DESIGN §5.1）。
 *
 * <p>{@code open_flag} 只有 {@link LeaveStatus#OPEN} 时为 1，其余状态必须为 {@code NULL}；
 * 配合唯一索引 {@code uk_leave_open (reservation_id, open_flag)}，
 * 「一条预约同时至多一条 OPEN」由数据库而非先查后插保证。
 * 每次结束都要把 {@code open_flag} 显式置回 {@code null}，否则该预约再也开不了第二条暂离。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_leave_record")
public class LeaveRecord {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属预约。 */
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    /** 用户 ID（冗余，按人按日计次直接用，不必回连预约表）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 座位 ID（冗余，巡查与统计直接定位座位）。 */
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** 原因码。 */
    @Column(name = "reason_code", nullable = false, length = 30)
    private String reasonCode;

    /** 事由，{@code need_note} 的原因必填。 */
    @Column(name = "note", length = 100)
    private String note;

    /** 登记时刻，按自然日计次即统计此列落在当天的记录。 */
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** 应返回时刻（分区上限与预约结束时间双重截断后的结果）。 */
    @Column(name = "planned_end_time", nullable = false)
    private LocalDateTime plannedEndTime;

    /** 实际返回时刻。 */
    @Column(name = "actual_return_time")
    private LocalDateTime actualReturnTime;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveStatus status = LeaveStatus.OPEN;

    /** ★ OPEN 时 =1，结束置 NULL。 */
    @Column(name = "open_flag")
    private Integer openFlag;

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
            status = LeaveStatus.OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

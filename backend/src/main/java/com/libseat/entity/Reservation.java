package com.libseat.entity;

import com.libseat.enums.ReservationStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预约主表实体（t_reservation）。一次预约 = 连续 N 小时，不跨天。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_reservation")
public class Reservation {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 预约人。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 座位。 */
    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** 冗余楼层，便于统计与列表展示。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 预约日期。 */
    @Column(name = "reserve_date", nullable = false)
    private LocalDate reserveDate;

    /** 起始时段序号 8~21。 */
    @Column(name = "start_slot", nullable = false)
    private Integer startSlot;

    /** 结束时段序号（开区间）。 */
    @Column(name = "end_slot", nullable = false)
    private Integer endSlot;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    /** 签到时间。 */
    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    /** 取消 / 释放时间。 */
    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    /** 管理员强制释放原因（≤100 字）。 */
    @Column(name = "admin_cancel_reason", length = 100)
    private String adminCancelReason;

    /**
     * 所属队伍（整桌下单时写入，个人预约为 null）。
     *
     * <p>★ 只是「这批预约属于同一次下单」的关联键，<b>不参与任何占用判定</b>：
     * 占用永远只看 slot 行与预约状态，组队不另立第二套占用真相（DESIGN §7.2）。
     */
    @Column(name = "group_id")
    private Long groupId;

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
            status = ReservationStatus.CONFIRMED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

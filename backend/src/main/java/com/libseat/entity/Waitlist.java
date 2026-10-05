package com.libseat.entity;

import com.libseat.enums.WaitlistStatus;
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
 * 候补队列的一条记录（t_waitlist）。
 *
 * <p>排队键是「分区 + 日期 + 起始时段」，<b>不含座位</b>：释放的是「一个位置」而不是
 * 「那一把椅子」。把 seat_id 写进队列，就会出现「那把椅子又被别人占了，整条队列卡死」。
 *
 * <p>{@code activeFlag} 是配合唯一索引用的老手法（与 {@code t_leave_record.open_flag} 同源）：
 * 活动中为 1，终态一律置 null，于是「同一用户同一键只能有一条活动记录」由数据库兜底，
 * 而历史记录可以无限多条。★ 只能通过 {@link #changeStatus} 改状态，
 * 直接 setStatus 会让 flag 与状态不一致 —— 要么把人永久卡在名额里，要么让唯一索引失效。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_waitlist")
public class Waitlist {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 候补人。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 楼层。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 分区（排队键之一）。 */
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 桌位（整桌候补用，普通区为 null）。 */
    @Column(name = "table_id")
    private Long tableId;

    /** 日期（排队键之一）。 */
    @Column(name = "reserve_date", nullable = false)
    private LocalDate reserveDate;

    /** 起始格（排队键之一）。 */
    @Column(name = "start_slot", nullable = false)
    private Integer startSlot;

    /** 结束格（开区间边界），确认时按此下单。 */
    @Column(name = "end_slot", nullable = false)
    private Integer endSlot;

    /** 状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WaitlistStatus status;

    /** 活动中 = 1；终态 = null（配合 {@code uk_waitlist_active}）。 */
    @Column(name = "active_flag")
    private Integer activeFlag;

    /** 置为 OFFERED 的时刻。 */
    @Column(name = "offered_time")
    private LocalDateTime offeredTime;

    /** 确认窗口截止时刻。 */
    @Column(name = "offer_expire_time")
    private LocalDateTime offerExpireTime;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) {
            createTime = now;
        }
        updateTime = now;
    }

    @PreUpdate
    void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    /**
     * 改状态的唯一入口：状态与 {@code activeFlag} 一起变。
     *
     * @param next 目标状态
     */
    public void changeStatus(WaitlistStatus next) {
        this.status = next;
        this.activeFlag = next.active() ? 1 : null;
    }
}

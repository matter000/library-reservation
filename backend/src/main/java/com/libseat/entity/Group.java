package com.libseat.entity;

import com.libseat.enums.GroupStatus;
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
 * 组队预约的队伍（t_group，DESIGN §7.1）。
 *
 * <p>★ 队伍本身<b>不持有占用</b>：座位占用一律来自锁定后生成的 {@code t_reservation}
 * （共享 {@code group_id}）。这样平面图、统计、暂离、候补释放这些「按占用说话」的地方
 * 不需要为组队另开一条判定分支 —— V1 就是靠这条红线才把并发问题收干净。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_group")
public class Group {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 组长（签到、取消、改约的权利都在他身上）。 */
    @Column(name = "leader_id", nullable = false)
    private Long leaderId;

    /** 楼层。 */
    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    /** 分区（规则口径的来源：是否允许组队、时长上限等）。 */
    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    /** 桌位；整桌下单必填。 */
    @Column(name = "table_id")
    private Long tableId;

    /** 预约日期。 */
    @Column(name = "reserve_date", nullable = false)
    private LocalDate reserveDate;

    /** 起始 30 分钟格（含）。 */
    @Column(name = "start_slot", nullable = false)
    private Integer startSlot;

    /** 结束 30 分钟格（不含）。 */
    @Column(name = "end_slot", nullable = false)
    private Integer endSlot;

    /** 6 位邀请码。 */
    @Column(name = "invite_code", length = 10)
    private String inviteCode;

    /** 邀请码失效时刻。 */
    @Column(name = "invite_expire_time")
    private LocalDateTime inviteExpireTime;

    /** 成团最少人数。 */
    @Column(name = "min_members", nullable = false)
    private Integer minMembers;

    /** 队伍人数上限（不超过桌位座位数）。 */
    @Column(name = "max_members", nullable = false)
    private Integer maxMembers;

    /** 锁定时刻：到点冻结成员并下单。 */
    @Column(name = "lock_time", nullable = false)
    private LocalDateTime lockTime;

    /** 状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GroupStatus status = GroupStatus.FORMING;

    /** 创建时间。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (updateTime == null) {
            updateTime = createTime;
        }
        if (status == null) {
            status = GroupStatus.FORMING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

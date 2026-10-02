package com.libseat.entity;

import com.libseat.enums.AttendStatus;
import com.libseat.enums.InviteStatus;
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
 * 队伍成员（t_group_member）。
 *
 * <p>{@code (group_id, user_id)} 唯一：一个人同一支队伍里只能有一条身份记录，
 * 「先婉拒再反悔」走状态迁移（DECLINED → INVITED → CONFIRMED），不再插新行 ——
 * 否则成团人数要按最后一条记录算，等于把聚合逻辑推给每一个读方。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_group_member")
public class GroupMember {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属队伍。 */
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** 成员。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 角色：LEADER / MEMBER。 */
    @Column(name = "member_role", nullable = false, length = 10)
    private String memberRole;

    /** 邀请状态。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "invite_status", nullable = false, length = 20)
    private InviteStatus inviteStatus = InviteStatus.INVITED;

    /** 确认时刻。 */
    @Column(name = "confirm_time")
    private LocalDateTime confirmTime;

    /** 锁定后分配到的座位。 */
    @Column(name = "seat_id")
    private Long seatId;

    /** 入座状态（组长签到只改预约，不改这里）。 */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "attend_status", nullable = false, length = 20)
    private AttendStatus attendStatus = AttendStatus.UNKNOWN;

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
        if (inviteStatus == null) {
            inviteStatus = InviteStatus.INVITED;
        }
        if (attendStatus == null) {
            attendStatus = AttendStatus.UNKNOWN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }
}

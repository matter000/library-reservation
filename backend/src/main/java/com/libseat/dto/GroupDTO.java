package com.libseat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 组队预约 DTO（DESIGN §12.1 S14-S17）。
 *
 * <p><b>设计红线</b>：成员能不能确认 / 能不能退出 / 队伍能不能取消，一律由后端算成
 * {@code canXxx + xxxDisabledReason} 返回，前端只渲染 —— 与「我的座位」「我的信用」同一套做法。
 * 邀请码只回给组长：给谁都等于把队伍变成公开入口。
 */
public class GroupDTO {

    /**
     * 建队请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupCreateRequest {

        /** 分区。 */
        @NotNull(message = "请选择分区")
        private Long zoneId;

        /** 桌位（整桌下单必填）。 */
        @NotNull(message = "请选择桌位")
        private Long tableId;

        /** 日期。 */
        @NotNull(message = "请选择日期")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;

        /** 起始 30 分钟格。 */
        @NotNull(message = "请选择起始时段")
        private Integer startSlot;

        /** 时长（小时）。 */
        @NotNull(message = "请选择时长")
        @Min(value = 1, message = "时长至少 1 小时")
        private Integer duration;

        /** 成团最少人数，省略用默认 2。 */
        @Min(value = 2, message = "成团人数至少 2 人")
        private Integer minMembers;

        /** 人数上限，省略用桌位座位数。 */
        @Min(value = 2, message = "人数上限至少 2 人")
        private Integer maxMembers;
    }

    /**
     * 按账号邀请。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InviteRequest {

        /** 被邀请人登录账号（学号）。 */
        @NotBlank(message = "请填写要邀请的账号")
        private String username;
    }

    /**
     * 邀请码加入。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinRequest {

        /** 6 位邀请码。 */
        @NotBlank(message = "请填写邀请码")
        private String inviteCode;
    }

    /**
     * 成员视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberVO {

        /** 成员记录 ID。 */
        private Long memberId;

        /** 用户 ID。 */
        private Long userId;

        /** 账号。 */
        private String username;

        /** 姓名。 */
        private String realName;

        /** LEADER / MEMBER。 */
        private String memberRole;

        /** 角色文案（组长 / 成员）。 */
        private String memberRoleText;

        /** 邀请状态枚举名。 */
        private String inviteStatus;

        /** 邀请状态文案。 */
        private String inviteStatusText;

        /** 确认时刻。 */
        private String confirmTime;

        /** 分配到的座位（锁定后）。 */
        private Long seatId;

        /** 座位编号（锁定后）。 */
        private String seatNo;

        /** 入座状态枚举名。 */
        private String attendStatus;

        /** 入座状态文案。 */
        private String attendStatusText;

        /** 本人现在能否确认。 */
        private Boolean canConfirm;

        /** 不能确认的原因。 */
        private String confirmDisabledReason;

        /** 本人现在能否退出。 */
        private Boolean canQuit;

        /** 不能退出的原因。 */
        private String quitDisabledReason;
    }

    /**
     * 队伍视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupVO {

        /** 队伍 ID。 */
        private Long groupId;

        /** 组长 ID。 */
        private Long leaderId;

        /** 组长姓名。 */
        private String leaderName;

        /** 楼层。 */
        private Long floorId;

        /** 楼层名。 */
        private String floorName;

        /** 分区。 */
        private Long zoneId;

        /** 分区名。 */
        private String zoneName;

        /** 桌位。 */
        private Long tableId;

        /** 桌号。 */
        private String tableNo;

        /** 日期。 */
        private String date;

        /** 起始格。 */
        private Integer startSlot;

        /** 结束格（不含）。 */
        private Integer endSlot;

        /** 时段文案。 */
        private String timeRange;

        /** 时长（小时）。 */
        private Integer durationHours;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 成团人数。 */
        private Integer minMembers;

        /** 人数上限。 */
        private Integer maxMembers;

        /** 已确认人数。 */
        private Integer confirmedCount;

        /** 待确认人数。 */
        private Integer invitedCount;

        /** 锁定时刻。 */
        private String lockTime;

        /** 距锁定还剩多少分钟（负数表示已过）。 */
        private Integer minutesToLock;

        /** 邀请码；仅组长可见。 */
        private String inviteCode;

        /** 邀请码失效时刻；仅组长可见。 */
        private String inviteExpireTime;

        /** 邀请码是否仍有效。 */
        private Boolean inviteAlive;

        /** 当前调用方是否为组长。 */
        private Boolean leader;

        /** 组长能否取消本队。 */
        private Boolean canCancel;

        /** 不能取消的原因。 */
        private String cancelDisabledReason;

        /** 当前调用方可做的动作提示（无队伍可操作时为空）。 */
        private String myInviteStatus;

        /** 成员列表。 */
        private List<MemberVO> members;
    }
}

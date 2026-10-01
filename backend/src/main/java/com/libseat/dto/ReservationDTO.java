package com.libseat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预约相关 DTO：创建请求 / 创建结果 / 预约 VO。
 */
public class ReservationDTO {

    /**
     * 提交预约请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationCreateRequest {

        /** 座位 ID。 */
        @NotNull(message = "请选择座位")
        private Long seatId;

        /** 预约日期 yyyy-MM-dd。 */
        @NotNull(message = "请选择日期")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private java.time.LocalDate date;

        /** 起始时段序号 8~21。 */
        @NotNull(message = "请选择起始时段")
        private Integer startSlot;

        /** 时长（小时），1/2/3/4，默认 2。 */
        private Integer duration;
    }

    /**
     * 预约创建结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationCreateVO {

        /** 预约 ID。 */
        private Long reservationId;

        /** 座位编号。 */
        private String seatNo;

        /** 楼层名称。 */
        private String floorName;

        /** 日期。 */
        private String date;

        /** 起始时段。 */
        private Integer startSlot;

        /** 结束时段（开区间）。 */
        private Integer endSlot;

        /** 时间区间文本，如 14:00-16:00。 */
        private String timeRange;

        /** 状态。 */
        private String status;
    }

    /**
     * 预约视图对象（★ canCancel / canCheckIn / checkInWindowText 由后端算好）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationVO {

        /** 预约 ID。 */
        private Long reservationId;

        /** 用户 ID。 */
        private Long userId;

        /** 账号。 */
        private String username;

        /** 座位 ID。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 区域。 */
        private String zone;

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 日期 yyyy-MM-dd。 */
        private String date;

        /** 起始时段。 */
        private Integer startSlot;

        /** 结束时段（开区间）。 */
        private Integer endSlot;

        /** 时间区间文本。 */
        private String timeRange;

        /** 时长（小时）。 */
        private Integer durationHours;

        /** 状态枚举。 */
        private String status;

        /** 状态中文文案。 */
        private String statusText;

        /** 签到时间。 */
        private String checkInTime;

        /** 取消 / 释放时间。 */
        private String cancelTime;

        /** 管理员释放原因。 */
        private String adminCancelReason;

        /** 创建时间。 */
        private String createTime;

        /** 是否可取消（后端计算）。 */
        private Boolean canCancel;

        /** 是否可签到（后端计算）。 */
        private Boolean canCheckIn;

        /** 签到窗口提示文本（后端计算）。 */
        private String checkInWindowText;

        /** 是否可登记暂离（后端算，前端只置灰）。 */
        private Boolean canLeave;

        /** 是否可「我已返回」（存在 OPEN 暂离时为真）。 */
        private Boolean canReturn;

        /** 是否可提前结束使用。 */
        private Boolean canFinish;

        /** 暂离按钮不可用时的具体原因（可用时为 null，杜绝「只置灰不给理由」）。 */
        private String leaveDisabledReason;

        /** 当前 OPEN 暂离的 ID（「我已返回」需要它，前端不必再查一次）。 */
        private Long activeLeaveId;

        /** 当前 OPEN 暂离的应归时刻 HH:mm。 */
        private String expectedReturnAt;

        /** 当前 OPEN 暂离距离应归还剩几分钟（已超时为负）。 */
        private Integer leaveRemainMin;
    }

    /**
     * 取消 / 签到结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationActionVO {

        /** 预约 ID。 */
        private Long reservationId;

        /** 变更后状态。 */
        private String status;

        /** 签到时间（签到成功时返回）。 */
        private String checkInTime;

        /** 操作时间（取消时返回）。 */
        private String cancelTime;

        /** 主动结束时刻（结束使用时返回）。 */
        private String finishTime;

        /** 结束使用后累计的连续主动结束次数。 */
        private Integer activeFinishStreak;

        /** 本次是否刚好触发一档连续主动结束奖励。 */
        private Boolean streakBonusAwarded;

        /** 操作后的信用分（让用户当场看到结果，而不是「回去刷新再猜」）。 */
        private Integer creditScore;
    }
}

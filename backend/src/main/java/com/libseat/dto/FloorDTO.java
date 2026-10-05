package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 楼层与时段相关 DTO。
 */
public class FloorDTO {

    /**
     * 楼层视图对象（含座位数 / 空闲数 / 占用率）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FloorVO {

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String name;

        /** 楼层编号。 */
        private String code;

        /** 描述。 */
        private String description;

        /** 排序号。 */
        private Integer sortOrder;

        /** 状态：OPEN / CLOSED。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 座位总数。 */
        private Long totalSeats;

        /** 空闲数。 */
        private Long freeSeats;

        /** 占用数（RESERVED + IN_USE）。 */
        private Long occupiedSeats;

        /** 不可用数（维护 / 停用 / 楼层关闭）。 */
        private Long unavailableSeats;

        /** 占用率（0~100）。 */
        private Double usageRate;

        /** 座位数（管理端列表用，等价于 totalSeats）。 */
        private Long seatCount;

        /** 创建时间。 */
        private String createTime;
    }

    /**
     * 时段选项。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlotOptionVO {

        /** 时段序号（30 分钟一格）。 */
        private Integer slotIndex;

        /** 标签，如 14:00-14:30。 */
        private String label;

        /** 开始时间 HH:mm。 */
        private String start;

        /** 结束时间 HH:mm。 */
        private String end;

        /** 是否可预约（判定含开放窗口与是否已过期，前端不再重复实现）。 */
        private Boolean bookable;

        /** 不可预约的原因，可预约时为 null。 */
        private String reason;

        /** 是否落在高峰窗口内。 */
        private Boolean peak;

        /** 是否为当前所处时段。 */
        private Boolean isCurrent;
    }

    /**
     * 用户端预约表单配置（来自规则中心，管理员改后立即生效，无需重启）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingConfigVO {

        /** 可提前预约天数（含当天）。 */
        private Integer advanceDays;

        /** 可选时长列表（小时）。 */
        private List<Integer> allowedDurations;

        /** 默认时长（小时）。 */
        private Integer defaultDuration;

        /** 签到窗口：开始前多少分钟。 */
        private Integer checkInBeforeMin;

        /** 签到窗口：开始后多少分钟。 */
        private Integer checkInAfterMin;

        /** 组队：距时段开始多少分钟锁定队伍并整桌下单。 */
        private Integer groupLockBeforeMin;

        /** 组队：邀请码有效时长（分钟）。 */
        private Integer groupInviteTtlMin;

        /** 组队：成团最少人数。 */
        private Integer groupMinMembers;

        /** 组队：组长签到后成员确认入座的宽限（分钟）。 */
        private Integer groupAttendGraceMin;

        /**
         * 候补 offer 的确认窗口（分钟，{@code waitlist.offer-min}）：说明文案要说得准，
         * 写死数字会在管理员改值后变成假说明。
         */
        private Integer waitlistOfferMin;

        /** 同一用户最多能挂几条活动候补（{@code waitlist.max-active}）。 */
        private Integer waitlistMaxActive;
    }

    /**
     * 楼层保存请求（新增 / 编辑）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FloorSaveRequest {

        /** 楼层名称。 */
        @jakarta.validation.constraints.NotBlank(message = "请输入楼层名称")
        @jakarta.validation.constraints.Size(max = 50, message = "楼层名称不能超过 50 位")
        private String name;

        /** 楼层编号。 */
        @jakarta.validation.constraints.NotBlank(message = "请输入楼层编号")
        @jakarta.validation.constraints.Size(max = 20, message = "楼层编号不能超过 20 位")
        private String code;

        /** 描述。 */
        @jakarta.validation.constraints.Size(max = 200, message = "描述不能超过 200 位")
        private String description;

        /** 排序号。 */
        private Integer sortOrder;

        /** 状态：OPEN / CLOSED。 */
        private String status;
    }

    /**
     * 楼层状态切换请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FloorStatusRequest {

        /** 目标状态：OPEN / CLOSED。 */
        @jakarta.validation.constraints.NotBlank(message = "请选择状态")
        private String status;
    }
}

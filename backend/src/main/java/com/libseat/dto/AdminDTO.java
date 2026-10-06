package com.libseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员相关 DTO：使用情况 / 预约记录 / 强制释放 / 用户列表。
 */
public class AdminDTO {

    /**
     * 占用人信息（★ 隐私红线：只有 realName + username + studentNo，无手机号）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OccupantVO {

        /** 姓名。 */
        private String realName;

        /** 学号 / 教工号（PRD §12-24 要求馆员据此确认"是不是这个人"）。 */
        private String studentNo;

        /** 账号。 */
        private String username;
    }

    /**
     * 使用情况一行。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageRowVO {

        /** 座位 ID。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 区域。 */
        private String zone;

        /** 行号。 */
        private Integer rowNo;

        /** 列号。 */
        private Integer colNo;

        /** 基础状态。 */
        private String baseStatus;

        /** 派生展示态。 */
        private String displayStatus;

        /** 占用人（未占用为 null）。 */
        private OccupantVO occupant;

        /** 预约 ID。 */
        private Long reservationId;

        /** 起始时段。 */
        private Integer startSlot;

        /** 结束时段。 */
        private Integer endSlot;

        /** 时间区间文本。 */
        private String timeRange;

        /** 预约状态。 */
        private String reservationStatus;
    }

    /**
     * 使用情况概览。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageSummaryVO {

        /** 总座位数。 */
        private Long total;

        /** 已占用（RESERVED + IN_USE）。 */
        private Long occupied;

        /** 空闲。 */
        private Long free;

        /** 不可用。 */
        private Long unavailable;

        /** 占用率（0~100）。 */
        private Double usageRate;
    }

    /**
     * 座位使用情况响应。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageVO {

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 日期。 */
        private String date;

        /** 时段序号。 */
        private Integer slotIndex;

        /** 时段标签。 */
        private String slotLabel;

        /** 概览。 */
        private UsageSummaryVO summary;

        /** 明细行。 */
        private java.util.List<UsageRowVO> rows;
    }

    /**
     * 管理员预约记录查询条件。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminReservationQuery {

        /** 关键字（账号 / 姓名 / 座位号）。 */
        private String keyword;

        /** 楼层 ID。 */
        private Long floorId;

        /** 起始日期。 */
        private java.time.LocalDate dateFrom;

        /** 结束日期。 */
        private java.time.LocalDate dateTo;

        /** 状态枚举。 */
        private String status;

        /** 页码（从 1 开始）。 */
        private Integer page;

        /** 每页条数。 */
        private Integer size;
    }

    /**
     * 管理员预约记录 VO（含用户身份，手机号不返回）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminReservationVO {

        /** 预约 ID。 */
        private Long reservationId;

        /** 用户 ID。 */
        private Long userId;

        /** 姓名。 */
        private String realName;

        /** 学号 / 教工号（PRD §12-24）。 */
        private String studentNo;

        /** 账号。 */
        private String username;

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 座位 ID。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 日期。 */
        private String date;

        /** 起始时段。 */
        private Integer startSlot;

        /** 结束时段。 */
        private Integer endSlot;

        /** 时间区间文本。 */
        private String timeRange;

        /** 时长（小时）。 */
        private Integer durationHours;

        /** 状态。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 签到时间。 */
        private String checkInTime;

        /** 取消 / 释放时间。 */
        private String cancelTime;

        /** 管理员释放原因。 */
        private String adminCancelReason;

        /** 创建时间。 */
        private String createTime;

        /** 是否可强制释放。 */
        private Boolean canRelease;
    }

    /**
     * 强制释放请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseRequest {

        /** 释放原因，必填 ≤100 字。 */
        @NotBlank(message = "请填写释放原因")
        @Size(max = 100, message = "释放原因不能超过 100 字")
        private String reason;
    }

    /**
     * 强制释放结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseVO {

        /** 预约 ID。 */
        private Long reservationId;

        /** 变更后状态。 */
        private String status;

        /** 释放原因。 */
        private String reason;
    }

    /**
     * 管理员端用户 VO（手机号脱敏）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO {

        /** 用户 ID。 */
        private Long userId;

        /** 账号。 */
        private String username;

        /** 姓名。 */
        private String realName;

        /** 脱敏手机号，如 138****1234。 */
        private String phoneMasked;

        /** 角色。 */
        private String role;

        /** 角色文案。 */
        private String roleText;

        /** 违约次数。 */
        private Integer noShowCount;

        /** 账号状态。 */
        private String status;

        /** 创建时间。 */
        private String createTime;
    }

    /**
     * 删除楼层结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteFloorVO {

        /** 已级联删除的座位数。 */
        private Long deletedSeats;

        /** 已级联删除的预约数。 */
        private Long deletedReservations;
    }

    /**
     * 删除座位结果。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteSeatVO {

        /** 被删除的座位 ID。 */
        private Long seatId;

        /** 已级联删除的预约数。 */
        private Long deletedReservations;
    }
}

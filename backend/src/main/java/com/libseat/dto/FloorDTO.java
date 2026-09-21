package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

        /** 时段序号 8~21。 */
        private Integer slotIndex;

        /** 标签，如 14:00-15:00。 */
        private String label;

        /** 开始时间 HH:mm。 */
        private String start;

        /** 结束时间 HH:mm。 */
        private String end;

        /** 是否可预约（未来日期全可约；今天需开始时间 > now）。 */
        private Boolean bookable;

        /** 是否为当前所处时段。 */
        private Boolean isCurrent;
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

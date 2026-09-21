package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统计相关 DTO：概览 / 趋势 / 时段热度 / 楼层利用率 / 状态分布。
 */
public class StatsDTO {

    /**
     * 6 大指标概览。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OverviewVO {

        /** 今日预约数（含已取消）。 */
        private Long todayReservations;

        /** 当前在场（CHECKED_IN）。 */
        private Long currentOnSite;

        /** 今日签到率（%）。 */
        private Double checkInRate;

        /** 今日未签到数。 */
        private Long todayNoShow;

        /** 座位总数。 */
        private Long totalSeats;

        /** 当前时段利用率（%）。 */
        private Double currentUsageRate;
    }

    /**
     * 预约量趋势。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendVO {

        /** 日期 yyyy-MM-dd。 */
        private String date;

        /** 预约数。 */
        private Long count;
    }

    /**
     * 时段热度（固定 14 项 8~21）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlotHeatVO {

        /** 时段序号。 */
        private Integer slotIndex;

        /** 标签，如 08:00-09:00。 */
        private String label;

        /** 预约占用次数。 */
        private Long count;
    }

    /**
     * 各楼层利用率。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FloorUsageVO {

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String name;

        /** 楼层编号。 */
        private String code;

        /** 座位总数。 */
        private Long totalSeats;

        /** 占用数。 */
        private Long occupied;

        /** 利用率（%）。 */
        private Double usageRate;
    }

    /**
     * 预约状态分布。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusDistVO {

        /** 状态枚举。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 数量。 */
        private Long count;
    }
}

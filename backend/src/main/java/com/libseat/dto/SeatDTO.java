package com.libseat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 座位相关 DTO：平面图 / 管理列表 / 保存 / 批量生成。
 */
public class SeatDTO {

    /**
     * 平面图中的一个座位（★ 不含任何占用人身份）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatItemVO {

        /** 座位 ID。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 行号。 */
        private Integer rowNo;

        /** 列号。 */
        private Integer colNo;

        /** 区域。 */
        private String zone;

        /** 基础状态：AVAILABLE / MAINTENANCE / DISABLED。 */
        private String baseStatus;

        /** 派生展示态：FREE / RESERVED / IN_USE / UNAVAILABLE。 */
        private String displayStatus;

        /**
         * 暂离时的应归时刻 {@code HH:mm}，非暂离为 null。
         *
         * <p>PRD §13.3 要求「暂离中 · 预计 14:35 返回」以文案优先于颜色呈现，
         * 该字段就是文案的数据源；只含时刻，不含任何占用人身份。
         */
        private String expectedReturnAt;

        /** 所属分区 ID。 */
        private Long zoneId;

        /** 所属桌位 ID（非组队区为 null）。 */
        private Long tableId;

        /**
         * 所属桌号（非组队区为 null）。
         *
         * <p>组队的下单单位是「桌」，平面图必须能把同一桌的座位说成一组；
         * 只给 tableId 的话前端只能显示「桌 7」这种内部编号。
         */
        private String tableNo;
    }

    /**
     * 分区在「当前用户 + 当前时刻」下的有效规则。
     *
     * <p>这些值全部由服务端算好，前端只负责展示与置灰：
     * 若前端自己再判一遍，就会出现「界面允许点、后端拒绝」的口径分裂（S0 门槛已踩过一次）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneRuleVO {

        /** 分区 ID。 */
        private Long zoneId;

        /** 分区名称。 */
        private String zoneName;

        /** 分区类型文案。 */
        private String zoneTypeText;

        /** 当前角色是否可预约本区（eligible_roles）。 */
        private Boolean eligible;

        /** 不可预约时的提示文案。 */
        private String ineligibleReason;

        /** 有效单次时长上限（分钟）。 */
        private Integer maxDurationMin;

        /** 有效可提前预约天数。 */
        private Integer advanceDays;

        /** 是否允许暂离。 */
        private Boolean leaveAllowed;

        /** 单次暂离封顶（分钟）。 */
        private Integer leaveMaxMin;

        /** 是否仅整组预约。 */
        private Boolean groupRequired;

        /** 是否支持组队预约（groupRequired 为真时必然支持；为假时决定平面图要不要提供选桌入口）。 */
        private Boolean allowGroup;

        /** 当前时段是否落在高峰窗口。 */
        private Boolean peakFlag;
    }

    /**
     * 平面图统计。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatMapStatsVO {

        /** 总数。 */
        private Long total;

        /** 空闲数。 */
        private Long free;

        /** 已预约数。 */
        private Long reserved;

        /** 使用中的数。 */
        private Long inUse;

        /** 暂离中的数（仍算占用，人不在座位上）。 */
        private Long onLeave;

        /** 不可用数。 */
        private Long unavailable;

        /** 占用率（0~100）。 */
        private Double usageRate;
    }

    /**
     * 座位平面图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatMapVO {

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 楼层编号。 */
        private String floorCode;

        /** 楼层状态：OPEN / CLOSED。 */
        private String floorStatus;

        /** 日期 yyyy-MM-dd。 */
        private String date;

        /** 时段序号。 */
        private Integer slotIndex;

        /** 时段标签。 */
        private String slotLabel;

        /** 本次过滤的分区 ID，null 表示整层。 */
        private Long zoneId;

        /** 本次过滤的分区名称。 */
        private String zoneName;

        /** 该分区对当前用户的有效规则（含可见性与可预约性判定结果）。 */
        private ZoneRuleVO zoneRules;

        /** 最大行号。 */
        private Integer maxRow;

        /** 最大列号。 */
        private Integer maxCol;

        /** 座位列表。 */
        private List<SeatItemVO> seats;

        /** 统计。 */
        private SeatMapStatsVO stats;
    }

    /**
     * 座位视图对象（管理端）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatVO {

        /** 座位 ID。 */
        private Long seatId;

        /** 楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 座位编号。 */
        private String seatNo;

        /** 行号。 */
        private Integer rowNo;

        /** 列号。 */
        private Integer colNo;

        /** 区域。 */
        private String zone;

        /** 基础状态。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 备注。 */
        private String remark;

        /** 是否存在未来有效预约（删除前置提示）。 */
        private Boolean hasFutureReservation;

        /** 创建时间。 */
        private String createTime;
    }

    /**
     * 座位保存请求（新增 / 编辑）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatSaveRequest {

        /** 楼层 ID。 */
        @NotNull(message = "请选择楼层")
        private Long floorId;

        /** 座位编号。 */
        @NotBlank(message = "请输入座位编号")
        @Size(max = 20, message = "座位编号不能超过 20 位")
        private String seatNo;

        /** 行号。 */
        @NotNull(message = "请输入行号")
        @Min(value = 1, message = "行号从 1 开始")
        @Max(value = 999, message = "行号不能超过 999")
        private Integer rowNo;

        /** 列号。 */
        @NotNull(message = "请输入列号")
        @Min(value = 1, message = "列号从 1 开始")
        @Max(value = 999, message = "列号不能超过 999")
        private Integer colNo;

        /** 区域。 */
        @Size(max = 20, message = "区域名不能超过 20 位")
        private String zone;

        /** 状态。 */
        private String status;

        /** 备注。 */
        @Size(max = 200, message = "备注不能超过 200 位")
        private String remark;
    }

    /**
     * 座位状态切换请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatStatusRequest {

        /** 目标状态：AVAILABLE / MAINTENANCE / DISABLED。 */
        @NotBlank(message = "请选择状态")
        private String status;
    }

    /**
     * 批量生成座位请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatBatchRequest {

        /** 楼层 ID。 */
        @NotNull(message = "请选择楼层")
        private Long floorId;

        /** 区域名，可空。 */
        @Size(max = 20, message = "区域名不能超过 20 位")
        private String zone;

        /** 行数。 */
        @NotNull(message = "请输入行数")
        @Min(value = 1, message = "行数至少为 1")
        @Max(value = 100, message = "行数不能超过 100")
        private Integer rows;

        /** 列数。 */
        @NotNull(message = "请输入列数")
        @Min(value = 1, message = "列数至少为 1")
        @Max(value = 100, message = "列数不能超过 100")
        private Integer cols;

        /** 编号前缀。 */
        @NotBlank(message = "请输入编号前缀")
        @Size(max = 8, message = "编号前缀不能超过 8 位")
        private String prefix;

        /** 起始序号。 */
        @NotNull(message = "请输入起始编号")
        @Min(value = 1, message = "起始编号至少为 1")
        private Integer startNo;

        /** 初始状态，默认 AVAILABLE。 */
        private String status;

        /** 起始行号，默认 1。★ 没有它，任何非空楼层上的批量生成都会整批撞第一行。 */
        @Min(value = 1, message = "起始行号至少为 1")
        @Max(value = 200, message = "起始行号不能超过 200")
        private Integer rowFrom;

        /** 起始列号，默认 1。 */
        @Min(value = 1, message = "起始列号至少为 1")
        @Max(value = 200, message = "起始列号不能超过 200")
        private Integer colFrom;

        /** 归属分区 ID（V2 起座位必须有归属；为空表示由服务端按区域名推断）。 */
        private Long zoneId;

        /** 归属桌位 ID，可空（非组队区不建模桌位）。 */
        private Long tableId;
    }

    /**
     * 批量生成结果。
     *
     * <p>{@code previewOnly=true} 时为冲突预览结果，未写入任何数据。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatBatchResultVO {

        /** 成功生成数（预览时=可生成的数量）。 */
        private Integer created;

        /** 跳过数（编号或坐标冲突）。 */
        private Integer skipped;

        /** 被跳过的座位编号。 */
        private List<String> skippedSeatNos;

        /** 被跳过的坐标，形如 {@code (1,1)} —— V1 只报编号，看不出同格重叠。 */
        private List<String> skippedPositions;

        /** 请求生成总数。 */
        private Integer totalRequested;

        /** 是否为预览（不落库）。 */
        private Boolean previewOnly;
    }
}

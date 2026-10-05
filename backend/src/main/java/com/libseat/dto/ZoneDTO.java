package com.libseat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分区相关 DTO。
 *
 * <p><b>设计红线</b>：{@link ZoneVO} 面向学生面，字段集合里不允许出现任何占用人身份信息；
 * 余座统计与平面图一样按「查询时刻」实时派生。
 */
public class ZoneDTO {

    /**
     * 分区视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneVO {

        /** 分区 ID。 */
        private Long zoneId;

        /** 所属楼层 ID。 */
        private Long floorId;

        /** 楼层名称。 */
        private String floorName;

        /** 分区名称。 */
        private String name;

        /** 分区编码。 */
        private String code;

        /** 分区类型枚举名。 */
        private String zoneType;

        /** 分区类型文案。 */
        private String zoneTypeText;

        /** 开放状态。 */
        private String status;

        /** 开放状态文案。 */
        private String statusText;

        /** 总座位数。 */
        private Long totalSeats;

        /** 空闲座位数（指定日期 + 时段）。 */
        private Long freeSeats;

        /** 占用座位数（指定日期 + 时段）。 */
        private Long occupiedSeats;

        /** 不可用（维护 / 停用）座位数。 */
        private Long unavailableSeats;

        /** 占用率（百分数，一位小数）。 */
        private Double usageRate;

        /** 当前用户是否可预约本分区（eligible_roles 判定）。 */
        private Boolean eligible;

        /** 有效可提前预约天数（已叠加楼层关闭等约束）。 */
        private Integer advanceDays;

        /** 有效单次时长上限（分钟，已叠加 §2.2 的取小规则）。 */
        private Integer maxDurationMin;

        /** 单日累计上限（分钟）；null 表示不设分区级上限。 */
        private Integer dailyCapMin;

        /** 是否允许暂离。 */
        private Boolean leaveAllowed;

        /** 单次暂离封顶（分钟）。 */
        private Integer leaveMaxMin;

        /** 每日暂离次数上限。 */
        private Integer leaveDailyLimit;

        /** 讨论音量等级枚举名。 */
        private String allowDiscuss;

        /** 讨论音量等级文案。 */
        private String discussText;

        /** 可见角色列表（管理页回填用；学生面另有 eligible 字段表达当前用户能否预约）。 */
        private List<String> visibleRoles;

        /** 可预约角色列表。 */
        private List<String> eligibleRoles;

        /** 是否支持组队预约。 */
        private Boolean allowGroup;

        /** 是否仅接受整组预约。 */
        private Boolean groupRequired;

        /**
         * 该分区在「查询日期 + 时段」是否处于高峰（静态窗口 ∨ 动态占用率认定）。
         *
         * <p>余量卡片要把它显示出来，用户才不至于走到平面图才发现被限流。
         */
        private Boolean peakFlag;
    }

    /**
     * 分区保存请求（新增 / 编辑）。
     */
    @Data
    public static class ZoneSaveRequest {

        /** 所属楼层。 */
        @NotNull(message = "请选择所属楼层")
        private Long floorId;

        /** 分区名称。 */
        @NotBlank(message = "请填写分区名称")
        @Size(max = 50, message = "分区名称不能超过 50 字")
        private String name;

        /** 楼层内唯一编码。 */
        @NotBlank(message = "请填写分区编码")
        @Size(max = 20, message = "分区编码不能超过 20 字")
        private String code;

        /** 分区类型枚举名。 */
        @NotBlank(message = "请选择分区类型")
        private String zoneType;

        /** 单次时长上限（分钟）。 */
        @NotNull(message = "请填写单次时长上限")
        @Min(value = 30, message = "单次时长上限不得小于 30 分钟")
        @Max(value = 840, message = "单次时长上限不得超过 840 分钟")
        private Integer maxDurationMin;

        /** 单日累计上限（分钟），可空。 */
        @Min(value = 30, message = "单日累计上限不得小于 30 分钟")
        private Integer dailyCapMin;

        /** 可提前预约天数。 */
        @NotNull(message = "请填写可提前预约天数")
        @Min(value = 1, message = "可提前预约天数至少为 1 天")
        @Max(value = 30, message = "可提前预约天数不得超过 30 天")
        private Integer advanceDays;

        /** 讨论音量：NO / LOW / YES。 */
        @NotBlank(message = "请选择讨论音量等级")
        private String allowDiscuss;

        /** 是否允许暂离。 */
        @NotNull(message = "请设置是否允许暂离")
        private Boolean allowLeave;

        /** 单次暂离封顶（分钟）。 */
        @Min(value = 5, message = "单次暂离封顶不得小于 5 分钟")
        @Max(value = 240, message = "单次暂离封顶不得超过 240 分钟")
        private Integer leaveMaxMin;

        /** 每日暂离次数上限。 */
        @Min(value = 0, message = "每日暂离次数不得为负")
        @Max(value = 20, message = "每日暂离次数不得超过 20 次")
        private Integer leaveDailyLimit;

        /** 是否支持组队预约。 */
        private Boolean allowGroup;

        /** 是否仅接受整组预约。 */
        private Boolean groupRequired;

        /** 可见角色枚举名列表。 */
        @NotEmpty(message = "请至少选择一个可见角色")
        private List<String> visibleRoles;

        /** 可预约角色枚举名列表。 */
        @NotEmpty(message = "请至少选择一个可预约角色")
        private List<String> eligibleRoles;

        /** 排序号。 */
        private Integer sortOrder;

        /** 备注。 */
        @Size(max = 200, message = "备注不能超过 200 字")
        private String remark;
    }

    /**
     * 分区状态切换请求。
     */
    @Data
    public static class ZoneStatusRequest {

        /** 目标状态：OPEN / CLOSED。 */
        @NotBlank(message = "请选择目标状态")
        private String status;
    }
}

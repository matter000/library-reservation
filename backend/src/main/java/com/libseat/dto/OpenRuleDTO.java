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
 * 开放档案 / 开放日历 DTO。
 *
 * <p>边界一律以 30 分钟时段序号表达，与 t_reservation_slot 同一口径；
 * VO 同时给出可读时间，避免管理面自己再拼一次 HH:mm。
 */
public class OpenRuleDTO {

    /**
     * 开放档案视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileVO {

        /** 档案 ID。 */
        private Long id;

        /** 日型。 */
        private String dayType;

        /** 日型文案。 */
        private String dayTypeText;

        /** 名称。 */
        private String name;

        /** 起始时段序号。 */
        private Integer openStart;

        /** 结束时段的开区间边界。 */
        private Integer openEnd;

        /** 起始时间文本。 */
        private String startTime;

        /** 结束时间文本。 */
        private String endTime;

        /** 当日型是否整体闭馆。 */
        private Boolean closed;

        /** 是否启用。 */
        private Boolean enabled;

        /** 该日型可约格数。 */
        private Integer slotCount;

        /** 备注。 */
        private String remark;
    }

    /**
     * 开放档案保存请求。
     */
    @Data
    public static class ProfileSaveRequest {

        /** 日型。 */
        @NotBlank(message = "请选择日型")
        private String dayType;

        /** 名称。 */
        @NotBlank(message = "请填写档案名称")
        @Size(max = 50, message = "名称不能超过 50 字")
        private String name;

        /** 起始时段序号（00:00 起算的 30 分钟格）。 */
        @NotNull(message = "请填写开放起始时段")
        @Min(value = 0, message = "起始时段不能为负")
        @Max(value = 95, message = "起始时段不得超过 47:30")
        private Integer openStart;

        /** 结束时段的开区间边界。 */
        @NotNull(message = "请填写开放结束时段的开区间边界")
        @Min(value = 1, message = "结束时段的开区间边界不能为 0")
        @Max(value = 96, message = "结束时段的开区间边界不得超过 48:00")
        private Integer openEnd;

        /** 是否整体闭馆。 */
        private Boolean closed;

        /** 是否启用。 */
        private Boolean enabled;

        /** 备注。 */
        @Size(max = 200, message = "备注不能超过 200 字")
        private String remark;
    }

    /**
     * 开放日历视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CalendarVO {

        /** 记录 ID。 */
        private Long id;

        /** 日期 yyyy-MM-dd。 */
        private String date;

        /** 星期文案。 */
        private String weekdayText;

        /** 覆盖日型，null 表示沿用。 */
        private String dayType;

        /** 覆盖起始时段。 */
        private Integer openStart;

        /** 覆盖结束时段的开区间边界。 */
        private Integer openEnd;

        /** 起始时间文本。 */
        private String startTime;

        /** 结束时间文本。 */
        private String endTime;

        /** 是否闭馆。 */
        private Boolean closed;

        /** 备注。 */
        private String remark;
    }

    /**
     * 开放日历保存请求。
     */
    @Data
    public static class CalendarSaveRequest {

        /** 日期 yyyy-MM-dd。 */
        @NotBlank(message = "请选择日期")
        private String date;

        /** 覆盖日型，可空。 */
        private String dayType;

        /** 覆盖起始时段，可空表示沿用。 */
        @Min(value = 0, message = "起始时段不能为负")
        @Max(value = 95, message = "起始时段不得超过 47:30")
        private Integer openStart;

        /** 覆盖结束时段的开区间边界，可空表示沿用。 */
        @Min(value = 1, message = "结束时段的开区间边界不能为 0")
        @Max(value = 96, message = "结束时段的开区间边界不得超过 48:00")
        private Integer openEnd;

        /** 是否闭馆。 */
        private Boolean closed;

        /** 备注。 */
        @Size(max = 200, message = "备注不能超过 200 字")
        private String remark;
    }

    /**
     * 某一天最终生效的开放规则（给管理面自检用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayRuleVO {

        /** 日期。 */
        private String date;

        /** 生效日型。 */
        private String dayType;

        /** 起始时段。 */
        private Integer openStart;

        /** 结束时段的开区间边界。 */
        private Integer openEnd;

        /** 起始时间文本。 */
        private String startTime;

        /** 结束时间文本。 */
        private String endTime;

        /** 是否闭馆。 */
        private Boolean closed;

        /** 可约格数。 */
        private Integer slotCount;

        /** 生效来源说明（档案名 / 日历备注）。 */
        private String source;

        /** 命中的高峰窗口名称。 */
        private List<String> peaks;
    }
}

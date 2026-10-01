package com.libseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 暂离相关 DTO：登记请求 / 暂离 VO / 原因字典 VO（DESIGN §5.2）。
 */
public class LeaveDTO {

    /**
     * 登记暂离请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaveStartRequest {

        /** 目标预约 ID（必须是本人 CHECKED_IN 的预约）。 */
        @NotNull(message = "请选择预约")
        private Long reservationId;

        /** 原因码，取自 {@code /api/leaves/reasons}。 */
        @NotBlank(message = "请选择暂离原因")
        private String reasonCode;

        /** 事由，need_note 的原因必填。 */
        @Size(max = 100, message = "事由不超过 100 字")
        private String note;
    }

    /**
     * 暂离记录 VO。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LeaveVO {

        /** 暂离 ID。 */
        private Long leaveId;

        /** 所属预约 ID。 */
        private Long reservationId;

        /** 座位编号。 */
        private String seatNo;

        /** 原因码。 */
        private String reasonCode;

        /** 原因文案。 */
        private String reasonText;

        /** 事由。 */
        private String note;

        /** 登记时刻 HH:mm。 */
        private String startTime;

        /** 应返回时刻 HH:mm。 */
        private String plannedEndTime;

        /** 实际返回时刻 HH:mm，未返回为 null。 */
        private String actualReturnTime;

        /** 距离应返回还剩几分钟；已超时为负数（界面据此显示「已超期 N 分钟」）。 */
        private Integer remainMin;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 是否已返回（前端据此决定按钮文案）。 */
        private Boolean returned;

        /** 预约的原始结束时刻 HH:mm，用于界面说明「剩余使用时间不顺延」。 */
        private String reservationEndTime;
    }

    /**
     * 原因字典 VO。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReasonVO {

        /** 原因码。 */
        private String code;

        /** 名称。 */
        private String name;

        /** 单次最长分钟。 */
        private Integer maxMin;

        /** 该原因每日次数上限。 */
        private Integer dailyLimit;

        /** 是否必须填事由。 */
        private Boolean needNote;
    }
}

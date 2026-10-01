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
 * 桌位相关 DTO。
 *
 * <p>{@code seatCount} 是声明值，{@code actualSeatCount} 由 t_seat 实时统计，
 * 两者不一致时 VO 给出 {@code seatCountMatches=false}，由管理面提示而不是静默改数。
 */
public class TableDTO {

    /**
     * 桌位视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableVO {

        /** 桌位 ID。 */
        private Long tableId;

        /** 所属分区 ID。 */
        private Long zoneId;

        /** 所属分区名称。 */
        private String zoneName;

        /** 所属楼层 ID。 */
        private Long floorId;

        /** 桌号。 */
        private String tableNo;

        /** 声明座位数。 */
        private Integer seatCount;

        /** 实际挂接座位数。 */
        private Long actualSeatCount;

        /** 声明与实际是否一致。 */
        private Boolean seatCountMatches;

        /** 状态。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 备注。 */
        private String remark;

        /** 挂接的座位清单。 */
        private List<SeatBrief> seats;
    }

    /**
     * 桌位上的座位简要信息（不含占用人身份）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeatBrief {

        /** 座位 ID。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 行号。 */
        private Integer rowNo;

        /** 列号。 */
        private Integer colNo;

        /** 座位基础状态。 */
        private String status;
    }

    /**
     * 桌位保存请求。
     */
    @Data
    public static class TableSaveRequest {

        /** 所属分区。 */
        @NotNull(message = "请选择所属分区")
        private Long zoneId;

        /** 桌号。 */
        @NotBlank(message = "请填写桌号")
        @Size(max = 20, message = "桌号不能超过 20 字")
        private String tableNo;

        /** 声明座位数。 */
        @NotNull(message = "请填写座位数")
        @Min(value = 2, message = "桌位座位数至少为 2")
        @Max(value = 12, message = "桌位座位数不得超过 12")
        private Integer seatCount;

        /** 状态，可空默认 AVAILABLE。 */
        private String status;

        /** 备注。 */
        @Size(max = 200, message = "备注不能超过 200 字")
        private String remark;
    }

    /**
     * 按桌生成座位请求。
     */
    @Data
    public static class TableGenerateRequest {

        /** 生成数量，省略则按桌位声明的 seatCount。 */
        @Min(value = 1, message = "生成数量至少为 1")
        @Max(value = 12, message = "生成数量不得超过 12")
        private Integer count;
    }
}

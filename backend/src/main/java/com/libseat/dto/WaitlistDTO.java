package com.libseat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 候补队列 DTO（DESIGN §8.3，T-V2-19）。
 *
 * <p><b>设计红线</b>：能不能确认、能不能退出，一律由服务端算成
 * {@code canXxx + xxxDisabledReason} 返回，前端只渲染。候补的确认窗口是会自己走完的，
 * 前端若自己判「还剩几分钟」，就会出现按钮亮着而 offer 已经顺延给下一位。
 */
public class WaitlistDTO {

    /**
     * 加入候补请求。
     *
     * <p>只填「分区 + 日期 + 起始时段 + 时长」，不填座位：候补要的是位置而不是某把椅子。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaitlistJoinRequest {

        /** 分区。 */
        @NotNull(message = "请选择分区")
        private Long zoneId;

        /** 日期。 */
        @NotNull(message = "请选择日期")
        @JsonFormat(pattern = "yyyy-MM-dd")
        private LocalDate date;

        /** 起始 30 分钟格。 */
        @NotNull(message = "请选择起始时段")
        private Integer startSlot;

        /** 时长（小时）。 */
        @NotNull(message = "请选择时长")
        @Min(value = 1, message = "时长至少 1 小时")
        private Integer duration;
    }

    /**
     * 候补视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaitlistVO {

        /** 候补记录 ID。 */
        private Long waitlistId;

        /** 楼层。 */
        private Long floorId;

        /** 分区。 */
        private Long zoneId;

        /** 分区名。 */
        private String zoneName;

        /** 日期。 */
        private String date;

        /** 起始格。 */
        private Integer startSlot;

        /** 结束格（不含）。 */
        private Integer endSlot;

        /** 时段文案。 */
        private String timeRange;

        /** 时长（小时）。 */
        private Integer durationHours;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 队内位置（1 起，终态为 null）。 */
        private Integer position;

        /** 该排队键上的队列长度。 */
        private Integer queueLength;

        /** 收到 offer 的时刻。 */
        private String offeredTime;

        /** offer 截止时刻。 */
        private String offerExpireTime;

        /** 距 offer 过期还剩多少分钟（负数表示已过）。 */
        private Integer minutesToExpire;

        /** 本人现在能否确认。 */
        private Boolean canConfirm;

        /** 不能确认的原因。 */
        private String confirmDisabledReason;

        /** 本人现在能否退出。 */
        private Boolean canQuit;

        /** 不能退出的原因。 */
        private String quitDisabledReason;

        /** 加入时刻。 */
        private String createTime;
    }
}

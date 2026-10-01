package com.libseat.dto;

import com.libseat.common.BizConstants;
import com.libseat.entity.Penalty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 处罚相关 DTO。
 */
public class PenaltyDTO {

    /**
     * 解除请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReleaseRequest {

        /** 解除说明（留痕，管理端与审计都要能看到）。 */
        @NotBlank(message = "请填写解除说明")
        @Size(max = 200, message = "解除说明不超过 200 字")
        private String note;
    }

    /**
     * 处罚视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PenaltyVO {

        /** 处罚 ID。 */
        private Long penaltyId;

        /** 用户 ID。 */
        private Long userId;

        /** 类型枚举名。 */
        private String type;

        /** 类型文案。 */
        private String typeText;

        /** 生效时刻。 */
        private String startTime;

        /** 结束时刻；null 表示需人工解除或随加分解除。 */
        private String endTime;

        /** 状态。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 触发理由。 */
        private String reason;

        /**
         * 由实体映射（唯一一份，避免两个入口各写一遍字段）。
         *
         * @param penalty 处罚实体
         * @return 视图
         */
        public static PenaltyVO of(Penalty penalty) {
            return PenaltyVO.builder()
                    .penaltyId(penalty.getId())
                    .userId(penalty.getUserId())
                    .type(penalty.getType().name())
                    .typeText(penalty.getType().getText())
                    .startTime(BizConstants.formatDateTime(penalty.getStartTime()))
                    .endTime(BizConstants.formatDateTime(penalty.getEndTime()))
                    .status(penalty.getStatus().name())
                    .statusText(penalty.getStatus().getText())
                    .reason(penalty.getReason())
                    .build();
        }
    }

    /**
     * 某人当前生效中的处罚（用户端「我的信用」与管理端共用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditSummaryVO {

        /** 当前信用分。 */
        private Integer creditScore;

        /** 生效中的处罚。 */
        private List<PenaltyVO> penalties;
    }
}

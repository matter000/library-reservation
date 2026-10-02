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
         * 怎么恢复（一句话说明）。
         *
         * <p>★ 这句话必须在后端算：三种处罚的解除方式完全不同（随回分解除 / 到期自动解除 /
         * 只能系统管理员解除），让前端按 {@code endTime} 是否为 null 去猜，
         * 猜错的就是「用户以为等等就好，其实必须到馆」。
         */
        private String recoverHint;

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
                    .recoverHint(recoverHint(penalty))
                    .build();
        }

        private static String recoverHint(Penalty penalty) {
            if (penalty.getEndTime() != null) {
                return "将于 " + BizConstants.formatDateTime(penalty.getEndTime()) + " 自动恢复";
            }
            return switch (penalty.getType()) {
                // PEAK_BAN 没有固定终点：分数回到阈值以上时由阶梯自动解除。
                // ★ 这里刻意不写死「90 分」：阈值是规则中心的可改项，写进文案就会说谎。
                case PEAK_BAN -> "信用分回升后自动解除，无需人工处理";
                case SEMESTER_BAN -> "需到馆处理，仅系统管理员可解除";
                // 3 天 / 7 天禁约都带 endTime，走不到这里
                case BOOK_BAN_3D, BOOK_BAN_7D -> "到期后自动恢复";
            };
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

        /**
         * 信用流水（正序）。
         *
         * <p>★ 必须给学生看：T-V2-14 验收 2 要求「+5 在流水中可见」。只给一个最终分数，
         * 用户就无法判断「这次回补到底落没落」，也只能靠猜来提申诉。
         */
        private List<CreditLogVO> logs;
    }

    /**
     * 信用流水视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreditLogVO {

        /** 流水 ID。 */
        private Long logId;

        /** 类型枚举名。 */
        private String kind;

        /** 类型文案。 */
        private String kindText;

        /** 变化量（加分为正、扣分为负；夹到边界时是「实际动过的值」）。 */
        private Integer delta;

        /** 记账后余额。 */
        private Integer balanceAfter;

        /** 说明。 */
        private String reason;

        /** 时刻。 */
        private String createTime;

        /**
         * 由实体映射。
         *
         * @param log 流水实体
         * @return 视图
         */
        public static CreditLogVO of(com.libseat.entity.CreditLog log) {
            return CreditLogVO.builder()
                    .logId(log.getId())
                    .kind(log.getKind().name())
                    .kindText(log.getKind().getText())
                    .delta(log.getDelta())
                    .balanceAfter(log.getBalanceAfter())
                    .reason(log.getReason())
                    .createTime(BizConstants.formatDateTime(log.getCreateTime()))
                    .build();
        }
    }
}

package com.libseat.dto;

import com.libseat.enums.PatrolAction;
import com.libseat.enums.PatrolLevel;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 巡查任务 DTO（DESIGN §11，T-V2-21）。
 *
 * <p>★ 看板行能不能处置、为什么不能，一律由服务端算成 {@code canXxx + xxxDisabledReason}：
 * 「释放」必须有可释放的预约，这条事实若由前端推断，馆员点下去才知道不行就成了报错体验。
 *
 * <p>★ 证据以 {@code evidenceItems} 的键值对下发（复用违规/申诉那一套渲染），
 * 馆员处置页因此能回答「系统凭什么说这张椅子有问题」，而不是只看到一个红灯。
 */
public class PatrolDTO {

    /**
     * 人工登记请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {

        /** 座位。 */
        @NotNull(message = "请选择座位")
        private Long seatId;

        /** 关联预约，可空（无预约占用）。 */
        private Long reservationId;

        /** 现场情况，必填。 */
        private String note;

        /** 等级，空则按 MID。 */
        private PatrolLevel level;

        /** 照片链接，可空。 */
        private String photoUrl;
    }

    /**
     * 处置请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HandleRequest {

        /** 动作：REMIND / RELEASE / VIOLATION / IGNORE。 */
        @NotNull(message = "请选择处置动作")
        private PatrolAction action;

        /** 处置说明，必填（审计与看板都要能回答「当时为什么这么处理」）。 */
        private String note;

        /** 照片链接，可空。 */
        private String photoUrl;
    }

    /**
     * 处置动作字典项。
     *
     * <p>★ 动作文案由服务端下发：馆员看到的按钮文字必须和他提交给 {@code /handle} 的动作码同源，
     * 否则 {@code PatrolAction} 改名后页面会挂着旧中文处置一个新语义。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionVO {

        /** 动作枚举名，提交给 {@code /handle} 的值。 */
        private String action;

        /** 动作文案。 */
        private String text;

        /** 处置后任务状态枚举名。 */
        private String targetStatus;

        /** 处置后任务状态文案。 */
        private String targetStatusText;
    }

    /**
     * 任务视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskVO {

        /** 任务 ID。 */
        private Long patrolId;

        /** 楼层。 */
        private Long floorId;

        /** 分区。 */
        private Long zoneId;

        /** 座位。 */
        private Long seatId;

        /** 座位编号。 */
        private String seatNo;

        /** 关联预约，可为空。 */
        private Long reservationId;

        /** 使用人 ID（无预约时为空）。 */
        private Long userId;

        /** 使用人姓名（无预约时为空）。 */
        private String userName;

        /** 类型枚举名。 */
        private String type;

        /** 类型文案。 */
        private String typeText;

        /** 等级枚举名。 */
        private String level;

        /** 等级文案。 */
        private String levelText;

        /** 生成依据文案。 */
        private String reason;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 可读证据。 */
        private List<AppealDTO.EvidenceItem> evidenceItems;

        /** 处置人。 */
        private Long handlerId;

        /** 处置说明。 */
        private String handleNote;

        /** 照片链接。 */
        private String photoUrl;

        /** 生成时刻。 */
        private String createTime;

        /** 处置时刻。 */
        private String handleTime;

        /** 是否还能处置。 */
        private Boolean canHandle;

        /** 不能处置的原因。 */
        private String handleDisabledReason;

        /** 是否可以强制释放。 */
        private Boolean canRelease;

        /** 不能释放的原因。 */
        private String releaseDisabledReason;

        /** 是否可以裁定违规。 */
        private Boolean canViolate;

        /** 不能裁定的原因。 */
        private String violateDisabledReason;
    }
}

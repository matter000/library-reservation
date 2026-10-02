package com.libseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 申诉与违规明细 DTO（DESIGN §6.3 / §12.1 S11-S13、§12.2 F8-F9）。
 *
 * <p><b>设计红线</b>：按钮可用性（能不能申诉、能不能升级、能不能裁定）一律由后端算清楚
 * 连同拒因一起返回，前端只负责渲染 —— 与「我的座位」那套 {@code canLeave/canReturn} 同源。
 * 时限类判定（24 小时）尤其不能交给前端，否则改一下系统时间就能翻案。
 */
public class AppealDTO {

    /**
     * 提交申诉。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppealRequest {

        /** 学生陈述的事由。 */
        @NotBlank(message = "请填写申诉事由")
        @Size(max = 500, message = "申诉事由不超过 500 字")
        private String reason;
    }

    /**
     * 裁定申诉。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JudgeRequest {

        /** 是否成立。 */
        @NotNull(message = "请选择裁定结果")
        private Boolean approve;

        /** 裁定说明；驳回时必填（学生要看得懂为什么不算）。 */
        @Size(max = 500, message = "裁定说明不超过 500 字")
        private String reply;
    }

    /**
     * 馆员直接撤销违规（超过申诉时限后的唯一救济入口）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevokeRequest {

        /** 撤销理由，必填并写进审计与违规记录。 */
        @NotBlank(message = "请填写撤销理由")
        @Size(max = 200, message = "撤销理由不超过 200 字")
        private String reason;
    }

    /**
     * 证据的一条可读项。
     *
     * <p>后端把证据 JSON 拆成「键 + 中文标签 + 值」的有序列表，而不是把 JSON 字符串原样丢给
     * 前端再翻译一遍：标签只有一份定义，新增证据字段也不会漏翻。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {

        /** 原始键（前端排查时能看到真实字段名）。 */
        private String key;

        /** 中文标签；未登记的键回退为原始键。 */
        private String label;

        /** 值（统一转字符串，保留 JSON 里的顺序与原文）。 */
        private String value;
    }

    /**
     * 违规明细视图（学生「我的违规」与馆员队列共用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ViolationVO {

        /** 违规 ID。 */
        private Long violationId;

        /** 违规主体（学生面即本人；馆员面用于定位是谁）。 */
        private Long userId;

        /** 关联预约，可为 null。 */
        private Long reservationId;

        /** 类型枚举名。 */
        private String type;

        /** 类型文案。 */
        private String typeText;

        /** 扣分（负数）。 */
        private Integer scoreDelta;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 来源：SYSTEM / LIBRARIAN。 */
        private String createdBy;

        /** 裁定时刻。 */
        private String createTime;

        /** 撤销时刻。 */
        private String revokeTime;

        /** 撤销理由。 */
        private String revokeReason;

        /** 原始证据 JSON。 */
        private String evidence;

        /** 证据可读视图。 */
        private List<EvidenceItem> evidenceItems;

        /** 申诉 ID；未申诉为 null。 */
        private Long appealId;

        /** 申诉状态枚举名。 */
        private String appealStatus;

        /** 申诉状态文案。 */
        private String appealStatusText;

        /** 裁定说明。 */
        private String appealReply;

        /** 裁定时刻。 */
        private String appealHandleTime;

        /** 是否还能提交申诉。 */
        private Boolean canAppeal;

        /** 不能申诉的原因（可直接展示给用户）。 */
        private String appealDisabledReason;

        /** 申诉剩余时限（小时，向下取整）；已定案或已撤销为 null。 */
        private Integer appealRemainHours;

        /** 是否可升级为终审（只有被驳回的申诉可以）。 */
        private Boolean canEscalate;

        /**
         * 馆员能否从这里直接撤销（F8）。
         *
         * <p>正在申诉流程中的违规必须回申诉队列裁定 —— 两个入口都能补分时，
         * 同一笔扣分有被回补两次的风险，所以这条判定和 {@code ViolationService.revoke}
         * 的入口校验保持同一套规则，前端不自己推断。
         */
        private Boolean canRevoke;

        /** 不能撤销的原因。 */
        private String revokeDisabledReason;
    }

    /**
     * 申诉队列视图（馆员面 / 管理面）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppealVO {

        /** 申诉 ID。 */
        private Long appealId;

        /** 被申诉的违规 ID。 */
        private Long violationId;

        /** 申诉人。 */
        private Long userId;

        /** 申诉人事由。 */
        private String reason;

        /** 状态枚举名。 */
        private String status;

        /** 状态文案。 */
        private String statusText;

        /** 裁定人角色（LIBRARIAN / SYS_ADMIN）。 */
        private String handlerRole;

        /** 裁定说明。 */
        private String reply;

        /** 提交时刻。 */
        private String createTime;

        /** 裁定时刻。 */
        private String handleTime;

        /** 当前调用方能否裁定这条申诉。 */
        private Boolean canJudge;

        /** 不能裁定的原因。 */
        private String judgeDisabledReason;

        /** 被申诉的违规（含证据可读视图，裁定页要用）。 */
        private ViolationVO violation;
    }
}

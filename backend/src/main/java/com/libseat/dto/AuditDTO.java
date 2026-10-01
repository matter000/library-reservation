package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志 DTO。
 */
public final class AuditDTO {

    private AuditDTO() {
        // 仅作 DTO 容器
    }

    /**
     * 审计记录视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditVO {

        private Long id;

        /** 操作人 ID；系统自动操作为 null。 */
        private Long actorId;

        /** 操作人角色。 */
        private String actorRole;

        /** 动作码。 */
        private String action;

        /** 动作中文文案。 */
        private String actionText;

        /** 对象类型。 */
        private String targetType;

        /** 对象 ID。 */
        private String targetId;

        /** 明细 JSON 原文。 */
        private String detail;

        /** 发生时间（yyyy-MM-dd HH:mm:ss）。 */
        private String createTime;
    }
}

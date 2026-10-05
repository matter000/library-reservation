package com.libseat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则配置相关 DTO。
 */
public final class RuleDTO {

    private RuleDTO() {
        // 仅作 DTO 容器
    }

    /**
     * 规则视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleVO {

        /** 作用域：GLOBAL / ZONE / DAY_TYPE。 */
        private String scope;

        /** 作用域对象 ID，GLOBAL 为空串。 */
        private String scopeId;

        /** 规则键。 */
        private String rkey;

        /** 规则值（原始字符串）。 */
        private String rvalue;

        /** 值类型：INT / BOOL / TIME / STRING / INT_LIST。 */
        private String valueType;

        /** 分组：OPEN / BOOK / LEAVE / CREDIT / PEAK / PATROL。 */
        private String rgroup;

        /** 是否允许在后台修改。 */
        private Boolean editable;

        /** 说明文案。 */
        private String description;
    }

    /**
     * 规则变更历史行（T-V2-22）。
     *
     * <p>★ 前后值取自审计 detail 的 {@code oldValue}/{@code newValue}，不是「当前值 + 猜的旧值」：
     * 一条规则被改过三次，第三条必须显示「A→B」而不是「B→B」。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleHistoryVO {

        /** 审计日志 ID。 */
        private Long logId;

        /** 变更时刻。 */
        private String createTime;

        /** 规则键。 */
        private String rkey;

        /** 规则说明（规则已删除时为空）。 */
        private String description;

        /** 分组。 */
        private String rgroup;

        /** 作用域。 */
        private String scope;

        /** 作用域对象 ID。 */
        private String scopeId;

        /** 改前值。 */
        private String oldValue;

        /** 改后值。 */
        private String newValue;

        /** 操作人 ID。 */
        private Long operatorId;

        /** 操作人姓名或账号。 */
        private String operatorName;

        /** 操作人角色。 */
        private String operatorRole;
    }

    /**
     * 规则更新请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleUpdateRequest {

        /** 作用域，省略表示 GLOBAL。 */
        private String scope;

        /** 作用域对象 ID，GLOBAL 省略即可。 */
        private String scopeId;

        /** 规则键（必须已由迁移脚本引入）。 */
        @NotBlank(message = "请输入规则键")
        private String rkey;

        /** 新值。 */
        @NotBlank(message = "请输入规则值")
        private String value;
    }
}

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

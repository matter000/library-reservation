package com.libseat.enums;

import lombok.Getter;

/**
 * 用户角色。
 */
@Getter
public enum UserRole {

    /** 普通用户。 */
    USER("USER", "普通用户"),

    /** 管理员。 */
    ADMIN("ADMIN", "管理员");

    private final String code;

    private final String text;

    UserRole(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

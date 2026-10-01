package com.libseat.enums;

import lombok.Getter;

/**
 * 用户角色（V2 四角色）。
 *
 * <p>V1 的 {@code USER} / {@code ADMIN} 由迁移脚本 {@code V2__role.sql} 分别改写为
 * {@code STUDENT} / {@code SYS_ADMIN}；旧 Token 携带的历史角色值在解析期即失败并返回 4010
 * （强制重新登录），系统内不做双版本角色兼容——避免"哪些 token 有效"变成隐式规则。
 *
 * <p>入口权限判定见 {@link com.libseat.security.RolePermission}；
 * 业务级资格（教师专区可见性、考研专座授权等）由 Service 层按分区规则校验。
 */
@Getter
public enum UserRole {

    /** 学生（本科生 / 研究生），可自助注册。 */
    STUDENT("STUDENT", "学生"),

    /** 教师 / 教职工，可自助注册，额外享有教师专区与更长单次时长。 */
    TEACHER("TEACHER", "教师"),

    /** 馆员：前台值班与现场巡查，后台账号，不开放注册。 */
    LIBRARIAN("LIBRARIAN", "馆员"),

    /** 系统管理员：规则配置、空间建模、账号与审计，后台账号，不开放注册。 */
    SYS_ADMIN("SYS_ADMIN", "系统管理员");

    private final String code;

    private final String text;

    UserRole(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

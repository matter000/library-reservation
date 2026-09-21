package com.libseat.common;

import lombok.Getter;

/**
 * 业务错误码枚举。
 *
 * <p>与 DESIGN §4.2 一一对应：0 / 4001 / 4010 / 4030 / 4040 / 4090 / 4091 / 4092 / 4093 / 4094 / 5000。
 */
@Getter
public enum ErrorCode {

    /** 成功。 */
    SUCCESS(0, "ok"),

    /** 参数错误（含 Bean Validation 失败）。 */
    PARAM_ERROR(4001, "参数错误"),

    /** 未登录 / Token 失效或过期。 */
    UNAUTHORIZED(4010, "登录已过期，请重新登录"),

    /** 无权限（角色不足）。 */
    FORBIDDEN(4030, "无权限访问"),

    /** 资源不存在。 */
    NOT_FOUND(4040, "资源不存在"),

    /** 业务冲突·通用（账号已注册、编号重复）。 */
    CONFLICT(4090, "业务冲突"),

    /** 座位时段冲突（并发抢座失败）。 */
    SEAT_SLOT_CONFLICT(4091, "该座位刚被他人预约，请重新选择"),

    /** 用户时段重叠。 */
    USER_SLOT_OVERLAP(4092, "您在该时段已有预约"),

    /** 当前状态不允许该操作。 */
    STATUS_NOT_ALLOWED(4093, "当前状态不允许该操作"),

    /** 存在关联数据，禁止删除。 */
    DELETE_FORBIDDEN(4094, "存在关联数据，禁止删除"),

    /** 系统内部错误。 */
    SYSTEM_ERROR(5000, "系统内部错误");

    private final int code;

    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据错误码推断 HTTP 状态码。
     *
     * @param code 业务错误码
     * @return HTTP 状态码
     */
    public static int toHttpStatus(int code) {
        if (code == UNAUTHORIZED.getCode()) {
            return 401;
        }
        if (code == FORBIDDEN.getCode()) {
            return 403;
        }
        if (code == NOT_FOUND.getCode()) {
            return 404;
        }
        if (code >= 4090 && code <= 4099) {
            return 409;
        }
        if (code == PARAM_ERROR.getCode()) {
            return 400;
        }
        return 500;
    }
}

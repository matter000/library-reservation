package com.libseat.common;

import lombok.Data;

/**
 * 统一响应体：{@code {code, message, data}}。
 *
 * @param <T> 数据体类型
 */
@Data
public class Result<T> {

    /** 0 表示成功，非 0 为业务错误码。 */
    private int code;

    /** 提示信息（失败时为可直接展示的中文）。 */
    private String message;

    /** 数据体。 */
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功（无数据）。
     *
     * @param <T> 数据体类型
     * @return Result
     */
    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功（带数据）。
     *
     * @param data 数据体
     * @param <T>  数据体类型
     * @return Result
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 失败（使用错误码默认文案）。
     *
     * @param errorCode 错误码
     * @param <T>       数据体类型
     * @return Result
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败（自定义中文提示）。
     *
     * @param errorCode 错误码
     * @param message   中文提示
     * @param <T>       数据体类型
     * @return Result
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 失败（自定义错误码 + 文案）。
     *
     * @param code    错误码
     * @param message 中文提示
     * @param <T>     数据体类型
     * @return Result
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}

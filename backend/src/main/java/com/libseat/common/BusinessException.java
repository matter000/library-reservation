package com.libseat.common;

import lombok.Getter;

/**
 * 业务异常：携带 {@link ErrorCode} 与可直接展示给用户的中文提示。
 *
 * <p>抛出即触发事务回滚（不要把 {@code setRollbackOnly()} 与业务异常混用，
 * 否则会抛 {@code UnexpectedRollbackException}）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    private final ErrorCode errorCode;

    /**
     * 使用错误码的默认文案。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
    }

    /**
     * 自定义中文提示。
     *
     * @param errorCode 错误码
     * @param message   中文提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.code = errorCode.getCode();
    }

    /**
     * 自定义错误码 + 文案。
     *
     * @param code    业务错误码
     * @param message 中文提示
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.errorCode = ErrorCode.SYSTEM_ERROR;
    }
}

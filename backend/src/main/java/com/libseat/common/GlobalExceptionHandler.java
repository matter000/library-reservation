package com.libseat.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/**
 * 全局异常处理器：把各类异常统一转换为 {@code {code, message, data}} 响应。
 *
 * <p>同时负责把业务错误码映射为 HTTP 状态码（4010→401、4030→403、409x→409 等）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** JSON 目标类型 → 面向用户的中文类型说明。 */
    private static final Map<String, String> TYPE_TEXT = Map.of(
            "Integer", "整数",
            "Long", "整数",
            "Short", "整数",
            "Boolean", "布尔值",
            "LocalDate", "yyyy-MM-dd 日期",
            "LocalDateTime", "yyyy-MM-dd HH:mm:ss 时间",
            "BigDecimal", "数值",
            "Double", "数值"
    );

    /**
     * 业务异常。
     *
     * @param e 业务异常
     * @return 统一响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return build(e.getCode(), e.getMessage());
    }

    /**
     * Bean Validation 失败（@Valid @RequestBody）。
     *
     * @param e 校验异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = "参数错误";
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        if (!errors.isEmpty()) {
            FieldError first = errors.get(0);
            String fieldMessage = first.getDefaultMessage();
            message = (fieldMessage == null || fieldMessage.isBlank()) ? "参数错误" : fieldMessage;
        }
        return build(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 参数约束异常（@Validated 在方法参数上）。
     *
     * @param e 校验异常
     * @return 统一响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        String message = "参数错误";
        if (!e.getConstraintViolations().isEmpty()) {
            ConstraintViolation<?> violation = e.getConstraintViolations().iterator().next();
            String violationMessage = violation.getMessage();
            message = (violationMessage == null || violationMessage.isBlank()) ? "参数错误" : violationMessage;
        }
        return build(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 请求体解析失败（常见于日期格式错误）。
     *
     * @param e 解析异常
     * @return 统一响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        String message = "请求参数格式错误，日期格式应为 yyyy-MM-dd";
        Throwable cause = e.getCause();
        if (cause instanceof MismatchedInputException mismatch) {
            // 只取最后一个路径节点的字段名，避免带上 "com.libseat.dto.XxxRequest" 前缀
            String field = null;
            List<JsonMappingException.Reference> path = mismatch.getPath();
            if (path != null && !path.isEmpty()) {
                field = path.get(path.size() - 1).getFieldName();
            }
            Class<?> target = mismatch.getTargetType();
            String typeName = (target == null) ? null : TYPE_TEXT.getOrDefault(target.getSimpleName(),
                    target.getSimpleName());
            if (field == null || field.isEmpty()) {
                message = (typeName == null) ? "请求参数格式错误" : "请求参数格式错误，应为 " + typeName;
            } else {
                message = (typeName == null)
                        ? "参数类型错误：" + field
                        : "参数类型错误：" + field + " 应为 " + typeName;
            }
        }
        return build(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 缺少必填请求参数。
     *
     * @param e 参数异常
     * @return 统一响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return build(ErrorCode.PARAM_ERROR.getCode(), "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 请求参数类型不匹配。
     *
     * @param e 类型异常
     * @return 统一响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(ErrorCode.PARAM_ERROR.getCode(), "参数类型错误：" + e.getName());
    }

    /**
     * 唯一索引冲突兜底（并发抢座/账号重复等）。
     *
     * @param e 数据完整性异常
     * @return 统一响应
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("唯一约束冲突：{}", e.getMostSpecificCause().getMessage());
        return build(ErrorCode.SEAT_SLOT_CONFLICT.getCode(), ErrorCode.SEAT_SLOT_CONFLICT.getMessage());
    }

    /**
     * 资源 / 处理器不存在（404）。
     *
     * <p>Spring 对 {@code @ExceptionHandler} 的匹配遵循「最具体优先」，
     * 因此本方法声明在兜底 {@code Exception} handler 之前即可稳定命中。
     *
     * <p>覆盖两种形态：
     * <ul>
     *   <li>{@code NoResourceFoundException}：静态资源缺失（Spring 6.1+ ResourceHttpRequestHandler 抛出）；</li>
     *   <li>{@code NoHandlerFoundException}：无匹配 Controller（需开启 throw-exception-if-no-handler-found）。</li>
     * </ul>
     *
     * @param e 404 相关异常
     * @return 统一响应，错误码 4040
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        log.warn("资源不存在：{}", e.getMessage());
        return build(ErrorCode.NOT_FOUND.getCode(), "资源不存在");
    }

    /**
     * 兜底异常。
     *
     * @param e 任意异常
     * @return 统一响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统内部错误", e);
        return build(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> build(int code, String message) {
        HttpStatus status = HttpStatus.valueOf(ErrorCode.toHttpStatus(code));
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}

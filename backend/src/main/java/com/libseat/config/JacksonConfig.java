package com.libseat.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 反序列化策略配置。
 *
 * <p>默认 Jackson 会对标量做静默强转：{@code 2.5 → 2}、{@code "2" → 2}，
 * 导致 {@code duration} 传入非整数时被悄悄截断并通过校验。
 * 这里关闭 Integer 的 String / Float 强转，使其抛 MismatchedInputException
 * （由 {@code GlobalExceptionHandler} 的 HttpMessageNotReadableException 分支转成 4001）。
 *
 * <p>注意：必须独立成配置类，不能放在 {@link WebConfig} 中 —— 后者构造依赖
 * JwtAuthInterceptor → ObjectMapper → ObjectMapperBuilder → 本 customizer，
 * 若同处一类会形成无法解析的循环引用。
 */
@Configuration
public class JacksonConfig {

    /**
     * 关闭 Integer 类型的字符串 / 浮点数强制转换。
     *
     * @return Jackson customizer
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.postConfigurer(mapper ->
                mapper.coercionConfigFor(LogicalType.Integer)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}

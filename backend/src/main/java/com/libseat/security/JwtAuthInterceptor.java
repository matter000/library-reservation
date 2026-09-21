package com.libseat.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.common.ErrorCode;
import com.libseat.common.Result;
import com.libseat.enums.UserRole;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权拦截器。
 *
 * <ol>
 *   <li>白名单（登录 / 注册 / H2 console / error）直接放行；</li>
 *   <li>OPTIONS 预检请求直接放行（CORS preflight 不带 Authorization 头）；</li>
 *   <li>无 Token / 解析失败 → 4010；</li>
 *   <li>路径以 {@code /api/admin/} 开头且 role ≠ ADMIN → 4030；</li>
 *   <li>写入 {@link UserContext}；afterCompletion 必须 clear()。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/h2-console",
            "/error"
    );

    private final JwtUtil jwtUtil;

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String uri = request.getRequestURI();

        // CORS 预检请求不带认证头，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        for (String prefix : WHITE_LIST) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }

        String token = resolveToken(request);
        if (token == null) {
            reject(response, ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
            return false;
        }

        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (RuntimeException e) {
            reject(response, ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
            return false;
        }

        Long userId;
        UserRole role;
        try {
            userId = Long.valueOf(claims.getSubject());
            role = UserRole.valueOf(String.valueOf(claims.get("role")));
        } catch (RuntimeException e) {
            reject(response, ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
            return false;
        }

        // 数据权限红线：/api/admin/** 必须 ADMIN
        if (uri.startsWith("/api/admin/") && role != UserRole.ADMIN) {
            log.warn("越权访问被拦截：userId={}, role={}, uri={}", userId, role, uri);
            reject(response, ErrorCode.FORBIDDEN.getCode(), "无权限访问，仅管理员可操作");
            return false;
        }

        UserContext.set(UserContext.CurrentUser.builder()
                .userId(userId)
                .username(String.valueOf(claims.get("username")))
                .realName(String.valueOf(claims.get("realName")))
                .role(role)
                .build());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            return null;
        }
        if (header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private void reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(ErrorCode.toHttpStatus(code));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, message)));
        response.getWriter().flush();
    }
}

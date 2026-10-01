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

/**
 * JWT 鉴权拦截器。
 *
 * <ol>
 *   <li>公开路径（{@link RolePermission#isPublic}）与 OPTIONS 预检直接放行；</li>
 *   <li>无 Token / 解析失败 / 角色值非法 → 4010；</li>
 *   <li>入口权限由 {@link RolePermission} 的「路径前缀 → 角色集合」矩阵判定 → 不通过 4030；</li>
 *   <li>写入 {@link UserContext}；afterCompletion 必须 clear()。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

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
        if (RolePermission.isPublic(uri)) {
            return true;
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

        // 入口权限矩阵（唯一事实来源，见 RolePermission）
        if (!RolePermission.permits(uri, role)) {
            log.warn("越权访问被拦截：userId={}, role={}, uri={}", userId, role, uri);
            reject(response, ErrorCode.FORBIDDEN.getCode(), "无权限访问");
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

package com.libseat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * UserContext 生命周期过滤器。
 *
 * <p>{@link JwtAuthInterceptor} 只在 {@code afterCompletion} 中清理 ThreadLocal，但
 * {@code DispatcherServlet#doDispatch} 在 {@code getHandler()} 返回 null（无匹配 Handler，
 * 例如未知 API 路径）时直接 {@code return}，不会回调 {@code afterCompletion}；
 * 此外静态资源、错误转发等路径也可能绕过拦截器。Tomcat 复用工作线程时，
 * 上一个用户的 {@link UserContext} 会残留在线程上，存在读到他人上下文的风险。
 *
 * <p>本过滤器以最高优先级覆盖 {@code /*}，在请求进入时先清空、在 finally 中再清空一次，
 * 保证任何路径（含白名单、静态资源、404、异常分支）都不会残留上下文。
 */
public class UserContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UserContext.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}

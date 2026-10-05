package com.libseat.config;

import com.libseat.security.JwtAuthInterceptor;
import com.libseat.security.UserContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：CORS + JWT 拦截器注册 + UserContext 清理过滤器 + SPA fallback。
 *
 * <p>Jackson 强转策略单独放在 {@link JacksonConfig}，避免与 ObjectMapper 形成循环引用。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    private final JwtAuthInterceptor jwtAuthInterceptor;

    /**
     * BCrypt 密码编码器（只引入 spring-security-crypto，无默认登录页 / CSRF）。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 注册 UserContext 清理过滤器（order 最高优先级，覆盖全部路径含白名单与 404 路径）。
     *
     * <p>仅靠 HandlerInterceptor#afterCompletion 清理存在理论漏洞：
     * DispatcherServlet 在 getHandler() 返回 null 时直接 return，不会回调 afterCompletion，
     * 而 Tomcat 复用工作线程 → ThreadLocal 可能残留。此处用 Filter 的 try/finally 兜底。
     *
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<UserContextFilter> userContextFilter() {
        FilterRegistrationBean<UserContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new UserContextFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(FilterRegistrationBean.HIGHEST_PRECEDENCE);
        registration.setName("userContextFilter");
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(FRONTEND_ORIGIN, "http://127.0.0.1:5173", "http://localhost:8080")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/h2-console/**",
                        "/error"
                );
    }

    /**
     * SPA fallback：history 路由刷新（如 /admin/statistics）时转发到 index.html。
     *
     * <p>只有前端构建产物已复制到 {@code classpath:static/index.html} 时才注册，
     * 否则会与 404 处理冲突。
     *
     * <p>绝不能用 {@code /**\/xxx} 这类写法 —— Spring Boot 3 用 PathPatternParser，
     * 不允许 {@code **} 之后再出现任何 pattern 元素，会直接导致应用启动失败。
     */
    @Controller
    @ConditionalOnResource(resources = "classpath:static/index.html")
    public static class SpaFallbackController {

        /**
         * 前端 history 路由白名单：命中则转发到 index.html，交给前端路由渲染。
         *
         * <p>采用「显式白名单」而非 {@code /{*path}} capture-all，原因：
         * capture-all 会把任意未定义路径（如 {@code /nothing/here}）也返回 200 + index.html，
         * 导致真正的 404 无法暴露（排查困难，且与约定的 4040 语义冲突）。
         * 白名单与前端 router 路由一一对应，未命中的路径仍走 404 分支。
         *
         * @return 转发视图名
         */
        @GetMapping({
                "/",
                "/login",
                "/register",
                "/floors",
                "/floors/{floorId}/seats",
                "/my/reservations",
                "/my/waitlist",
                "/groups",
                "/groups/create",
                "/credit",
                "/profile",
                "/staff",
                "/staff/appeals",
                "/admin",
                "/admin/floors",
                "/admin/floors/{floorId}/seats",
                "/admin/usage",
                "/admin/reservations",
                "/admin/statistics",
                "/admin/users",
                "/admin/rules",
                "/admin/violations",
                "/admin/zones",
                "/admin/tables",
                "/admin/open"
        })
        public String forward() {
            return "forward:/index.html";
        }
    }
}

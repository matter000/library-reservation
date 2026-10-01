package com.libseat.security;

import com.libseat.enums.UserRole;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 入口权限矩阵：<b>路径前缀 → 允许角色集合</b>，是全系统入口鉴权的唯一事实来源。
 *
 * <p>为什么集中在这里：V1 把「是否 ADMIN」硬编码在 {@link JwtAuthInterceptor} 里
 * （{@code uri.startsWith("/api/admin/")}），V2 角色增至 4 个、命名空间增至 3 个，
 * 若散落成多处 if-else，新增接口时极易只改一处而产生权限漂移（V1 CODE-AUDIT B1 的同类教训）。
 *
 * <p>匹配规则：按<b>最长前缀</b>命中；未命中任何规则表示「仅需登录」。
 * 公开路径（登录 / 注册 / H2 控制台 / 错误页）由 {@link #isPublic(String)} 判定。
 *
 * <p>★ 不含业务级资格判断：教师专区可见性、考研专座授权、信用处罚闸门等一律在 Service 层实现。
 */
public final class RolePermission {

    /** 任意已登录角色。 */
    private static final Set<UserRole> ANY_LOGGED_IN = Set.of(
            UserRole.STUDENT, UserRole.TEACHER, UserRole.LIBRARIAN, UserRole.SYS_ADMIN);

    /** 馆员作业面：馆员 + 系统管理员。 */
    private static final Set<UserRole> STAFF = Set.of(UserRole.LIBRARIAN, UserRole.SYS_ADMIN);

    /** 系统管理面：仅系统管理员。 */
    private static final Set<UserRole> ADMIN_ONLY = Set.of(UserRole.SYS_ADMIN);

    /** 无需 Token 的路径前缀。 */
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/h2-console",
            "/error"
    );

    private static final Map<String, Set<UserRole>> RULES = buildRules();

    private RolePermission() {
        // 工具类禁止实例化
    }

    private static Map<String, Set<UserRole>> buildRules() {
        Map<String, Set<UserRole>> rules = new LinkedHashMap<>();
        rules.put("/api/admin/", ADMIN_ONLY);
        rules.put("/api/staff/", STAFF);
        rules.put("/api/", ANY_LOGGED_IN);
        return Map.copyOf(rules);
    }

    /**
     * 是否为公开路径（无需 Token）。
     *
     * @param uri 请求 URI
     * @return 是否公开
     */
    public static boolean isPublic(String uri) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 命中该 URI 的角色集合。
     *
     * @param uri 请求 URI
     * @return 允许角色集合；空集表示无匹配规则（仅需登录）
     */
    public static Set<UserRole> allowedRoles(String uri) {
        String matched = null;
        for (String prefix : RULES.keySet()) {
            if (uri.startsWith(prefix) && (matched == null || prefix.length() > matched.length())) {
                matched = prefix;
            }
        }
        return matched == null ? Set.of() : RULES.get(matched);
    }

    /**
     * 判断某角色能否访问某 URI。
     *
     * @param uri  请求 URI
     * @param role 当前登录角色
     * @return 是否允许
     */
    public static boolean permits(String uri, UserRole role) {
        Set<UserRole> allowed = allowedRoles(uri);
        return role != null && (allowed.isEmpty() || allowed.contains(role));
    }
}

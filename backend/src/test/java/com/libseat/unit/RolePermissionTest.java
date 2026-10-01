package com.libseat.unit;

import com.libseat.enums.UserRole;
import com.libseat.security.RolePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2 入口权限矩阵单测：每个前缀 × 每个角色，含「无匹配规则」分支。
 *
 * <p>这里只测矩阵本身（纯函数，不起 Spring 上下文）；
 * 端到端的 4030 行为由 {@code SecurityApiTest.roleMatrix} 覆盖。
 */
class RolePermissionTest {

    @Test
    @DisplayName("公开路径无需 Token，且不误放行其它 /api 路径")
    void publicPaths() {
        assertTrue(RolePermission.isPublic("/api/auth/login"));
        assertTrue(RolePermission.isPublic("/api/auth/register"));
        assertTrue(RolePermission.isPublic("/h2-console/login.jsp"));
        assertTrue(RolePermission.isPublic("/error"));
        assertFalse(RolePermission.isPublic("/api/auth/me"), "me 接口必须登录");
        assertFalse(RolePermission.isPublic("/api/floors"));
        assertFalse(RolePermission.isPublic("/api/admin/floors"));
    }

    @Test
    @DisplayName("最长前缀命中：/api/admin/ 优先于 /api/")
    void longestPrefixWins() {
        assertTrue(RolePermission.permits("/api/admin/rules", UserRole.SYS_ADMIN));
        assertFalse(RolePermission.permits("/api/admin/rules", UserRole.LIBRARIAN));
        assertFalse(RolePermission.permits("/api/admin/rules", UserRole.STUDENT));
        assertFalse(RolePermission.permits("/api/admin/rules", UserRole.TEACHER));
    }

    @Test
    @DisplayName("馆员面：馆员与系统管理员可进，学生与教师不可")
    void staffNamespace() {
        assertTrue(RolePermission.permits("/api/staff/usage", UserRole.LIBRARIAN));
        assertTrue(RolePermission.permits("/api/staff/usage", UserRole.SYS_ADMIN));
        assertFalse(RolePermission.permits("/api/staff/usage", UserRole.STUDENT));
        assertFalse(RolePermission.permits("/api/staff/usage", UserRole.TEACHER));
    }

    @Test
    @DisplayName("学生面：四个已登录角色均可访问")
    void studentNamespace() {
        for (UserRole role : UserRole.values()) {
            assertTrue(RolePermission.permits("/api/floors", role), role + " 应可访问 /api/floors");
            assertTrue(RolePermission.permits("/api/reservations/my", role), role + " 应可访问本人预约");
        }
    }

    @Test
    @DisplayName("无匹配规则 = 仅需登录；角色为 null 一律拒绝")
    void unmatchedAndNullRole() {
        // 前端 SPA 路由不落在 /api 命名空间内 → 不命中任何规则，表示「仅需登录」
        assertTrue(RolePermission.permits("/floors", UserRole.STUDENT),
                "未命中规则的路径表示仅需登录");
        assertTrue(RolePermission.allowedRoles("/floors").isEmpty(),
                "未命中时角色集合应为空（表示不限制具体角色）");
        assertTrue(RolePermission.permits("/api/config/slots", UserRole.TEACHER),
                "/api/ 前缀命中「任意已登录角色」");
        assertFalse(RolePermission.permits("/floors", null), "null 角色必须拒绝");
        assertFalse(RolePermission.permits("/api/admin/floors", null));
    }

    @Test
    @DisplayName("V1 遗留角色值不再是合法枚举（Token 解析期即失败 → 4010 强制重登）")
    void legacyRoleValuesAreRejected() {
        // 若有人为「兼容旧 token」把 USER / ADMIN 加回枚举，本用例会失败：
        // 系统的契约是旧 token 一律失效，而不是维持两套角色语义。
        assertThrows(IllegalArgumentException.class, () -> UserRole.valueOf("USER"));
        assertThrows(IllegalArgumentException.class, () -> UserRole.valueOf("ADMIN"));
    }
}

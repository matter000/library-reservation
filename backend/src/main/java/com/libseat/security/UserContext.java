package com.libseat.security;

import com.libseat.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文（ThreadLocal），请求结束必须 {@link #clear()}。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
        // 工具类禁止实例化
    }

    /**
     * 存放当前用户。
     *
     * @param user 当前用户
     */
    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /**
     * 取当前用户（未登录返回 null）。
     *
     * @return 当前用户
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户 ID。
     *
     * @return 用户 ID
     */
    public static Long getUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    /**
     * 取当前用户角色。
     *
     * @return 角色
     */
    public static UserRole getRole() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getRole();
    }

    /**
     * 清空上下文（必须在 afterCompletion 中调用）。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前登录用户快照。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUser {

        /** 用户 ID。 */
        private Long userId;

        /** 账号。 */
        private String username;

        /** 姓名。 */
        private String realName;

        /** 角色。 */
        private UserRole role;
    }
}

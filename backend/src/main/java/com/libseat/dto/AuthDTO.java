package com.libseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证相关 DTO：注册 / 登录 / 用户信息。
 */
public class AuthDTO {

    /**
     * 注册请求（★ 后端强制 role=USER，不接受前端传 role）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {

        /** 登录账号，3~20 位字母数字下划线。 */
        @NotBlank(message = "请输入账号")
        @Size(min = 3, max = 20, message = "账号长度需为 3-20 位")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "账号只能包含字母、数字和下划线")
        private String username;

        /** 密码，≥6 位。 */
        @NotBlank(message = "请输入密码")
        @Size(min = 6, max = 32, message = "密码长度至少 6 位")
        private String password;

        /** 确认密码。 */
        @NotBlank(message = "请再次输入密码")
        private String confirmPassword;

        /** 真实姓名。 */
        @NotBlank(message = "请输入姓名")
        @Size(max = 50, message = "姓名长度不能超过 50 位")
        private String realName;

        /** 手机号（选填）。 */
        @Pattern(regexp = "^(1[3-9]\\d{9})?$", message = "手机号格式不正确")
        private String phone;
    }

    /**
     * 登录请求。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {

        /** 账号。 */
        @NotBlank(message = "请输入账号")
        private String username;

        /** 密码。 */
        @NotBlank(message = "请输入密码")
        private String password;
    }

    /**
     * 修改密码请求。
     *
     * <p>仅用于已登录用户修改自己的密码，不接受 userId 入参（防水平越权）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangePasswordRequest {

        /** 原密码。 */
        @NotBlank(message = "请输入原密码")
        private String oldPassword;

        /** 新密码，≥6 位。 */
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, max = 32, message = "密码长度至少 6 位")
        private String newPassword;

        /** 确认新密码。 */
        @NotBlank(message = "请再次输入新密码")
        private String confirmPassword;
    }

    /**
     * 用户信息。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {

        /** 用户 ID。 */
        private Long userId;

        /** 账号。 */
        private String username;

        /** 姓名。 */
        private String realName;

        /** 角色：USER / ADMIN。 */
        private String role;

        /** 脱敏手机号。 */
        private String phoneMasked;

        /** 违约次数。 */
        private Integer noShowCount;
    }

    /**
     * 登录响应。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {

        /** Token。 */
        private String token;

        /** Token 类型，固定 Bearer。 */
        private String tokenType;

        /** 有效期（秒）。 */
        private Long expiresIn;

        /** 用户信息。 */
        private UserInfo user;
    }
}

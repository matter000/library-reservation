package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.AuthDTO;
import com.libseat.security.UserContext;
import com.libseat.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 当前用户。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册（★ 后端强制 role=USER）。
     *
     * @param request 注册请求
     * @return 用户信息
     */
    @PostMapping("/register")
    public Result<AuthDTO.UserInfo> register(@Valid @RequestBody AuthDTO.RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return Token + 用户信息
     */
    @PostMapping("/login")
    public Result<AuthDTO.LoginResponse> login(@Valid @RequestBody AuthDTO.LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 当前登录用户信息。
     *
     * @return 用户信息
     */
    @GetMapping("/me")
    public Result<AuthDTO.UserInfo> me() {
        UserContext.CurrentUser current = UserContext.get();
        if (current == null) {
            return Result.fail(4010, "登录已过期，请重新登录");
        }
        return Result.ok(authService.me(current.getUserId()));
    }

    /**
     * 修改当前登录用户的密码。
     *
     * <p>用户 ID 取自 Token（UserContext），不接受前端传入，天然防水平越权。
     *
     * @param request 修改密码请求
     * @return 空响应
     */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@Valid @RequestBody AuthDTO.ChangePasswordRequest request) {
        UserContext.CurrentUser current = UserContext.get();
        if (current == null) {
            return Result.fail(4010, "登录已过期，请重新登录");
        }
        authService.changePassword(current.getUserId(), request);
        return Result.ok();
    }
}

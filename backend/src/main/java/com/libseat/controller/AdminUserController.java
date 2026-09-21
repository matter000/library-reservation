package com.libseat.controller;

import com.libseat.common.PageResult;
import com.libseat.common.Result;
import com.libseat.dto.AdminDTO;
import com.libseat.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员·用户列表（P1 可降级，手机号脱敏）。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * 用户分页列表。
     *
     * @param keyword 关键字（账号 / 姓名）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<AdminDTO.UserVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        int safePage = (page == null || page < 1) ? 1 : page;
        int safeSize = (size == null || size < 1) ? 20 : size;
        return Result.ok(adminUserService.page(keyword, safePage, safeSize));
    }
}

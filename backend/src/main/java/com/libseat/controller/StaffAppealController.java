package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.AppealDTO;
import com.libseat.enums.AppealStatus;
import com.libseat.security.UserContext;
import com.libseat.service.AppealService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 馆员面·申诉复核接口（DESIGN §12.2 F9）。
 *
 * <p>★ 复核与终审是同一入口的两种权限：{@code PENDING} 馆员可裁，
 * {@code ESCALATED}（学生升级件）只有系统管理员能裁 —— 判定在
 * {@link AppealService#judge} 里，不放前端菜单，因为「隐藏入口」拦不住直接打接口。
 */
@RestController
@RequestMapping("/api/staff/appeals")
@RequiredArgsConstructor
public class StaffAppealController {

    private final AppealService appealService;

    /**
     * 申诉队列（含被申诉违规的证据可读视图）。
     *
     * @param status 状态过滤，省略表示全部
     * @return 队列（旧的在前）
     */
    @GetMapping
    public Result<List<AppealDTO.AppealVO>> queue(@RequestParam(required = false) AppealStatus status) {
        return Result.ok(appealService.queue(status, UserContext.getRole()));
    }

    /**
     * 裁定申诉。
     *
     * @param id      申诉 ID
     * @param request 裁定结果与说明
     * @return 裁定后的申诉
     */
    @PostMapping("/{id}/judge")
    public Result<AppealDTO.AppealVO> judge(@PathVariable Long id,
                                            @Valid @RequestBody AppealDTO.JudgeRequest request) {
        return Result.ok(appealService.judge(id, Boolean.TRUE.equals(request.getApprove()),
                request.getReply(), UserContext.getUserId(), UserContext.getRole()));
    }
}

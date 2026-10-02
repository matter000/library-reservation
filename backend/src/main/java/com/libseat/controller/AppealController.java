package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.AppealDTO;
import com.libseat.security.UserContext;
import com.libseat.service.AppealService;
import com.libseat.service.ViolationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生面·违规明细与申诉接口（DESIGN §12.1 S11/S12/S13）。
 *
 * <p>路径同时挂了 {@code /api/me/violations}（读自己的）与 {@code /api/violations/{id}/appeal}
 * （对某条违规做事），两者都属于「学生侧的信用与违规」这一件事，故共用一个控制器；
 * 一律从 {@link UserContext} 取人，不接受前端传来的 userId —— 否则任何人都能替别人申诉。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AppealController {

    private final ViolationService violationService;

    private final AppealService appealService;

    /**
     * 我的违规明细（含证据可读视图与申诉入口可用性）。
     *
     * @return 违规列表
     */
    @GetMapping("/me/violations")
    public Result<List<AppealDTO.ViolationVO>> myViolations() {
        return Result.ok(violationService.listMine(UserContext.getUserId()));
    }

    /**
     * 提交申诉。
     *
     * @param id      违规 ID
     * @param request 事由
     * @return 申诉结果
     */
    @PostMapping("/violations/{id}/appeal")
    public Result<AppealDTO.AppealVO> submit(@PathVariable Long id,
                                             @Valid @RequestBody AppealDTO.AppealRequest request) {
        return Result.ok(appealService.submit(id, UserContext.getUserId(), UserContext.getRole(),
                request.getReason()));
    }

    /**
     * 对驳回不服，升级至系统管理员终审。
     *
     * @param id 违规 ID
     * @return 升级后的申诉
     */
    @PostMapping("/violations/{id}/appeal/escalate")
    public Result<AppealDTO.AppealVO> escalate(@PathVariable Long id) {
        return Result.ok(appealService.escalate(id, UserContext.getUserId(), UserContext.getRole()));
    }
}

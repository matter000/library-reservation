package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.StaffDashboardDTO;
import com.libseat.service.StaffDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 馆员面·监控大屏（TASKS-V2 T-V2-22）。
 *
 * <p>挂在 {@code /api/staff/} 下而不是 {@code /api/admin/}：大屏是给在馆里走动的人看的，
 * 馆员不需要（也不应该有）系统管理员的账号才能看到全馆占用。
 * 读到的内容不含「谁坐在哪个椅子」的姓名，只有数字 —— 明细在平面图与巡查看板里。
 */
@RestController
@RequestMapping("/api/staff/dashboard")
@RequiredArgsConstructor
public class StaffDashboardController {

    private final StaffDashboardService staffDashboardService;

    /**
     * 大屏整页数据（单接口，前端定时刷新）。
     *
     * @return 大屏 VO
     */
    @GetMapping
    public Result<StaffDashboardDTO.DashboardVO> dashboard() {
        return Result.ok(staffDashboardService.dashboard(LocalDateTime.now()));
    }
}

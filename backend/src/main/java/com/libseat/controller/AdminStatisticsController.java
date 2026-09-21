package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.StatsDTO;
import com.libseat.service.AdminStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理员·数据统计接口。
 */
@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class AdminStatisticsController {

    private final AdminStatisticsService adminStatisticsService;

    /**
     * 6 大指标概览。
     *
     * @return 概览
     */
    @GetMapping("/overview")
    public Result<StatsDTO.OverviewVO> overview() {
        return Result.ok(adminStatisticsService.overview());
    }

    /**
     * 近 N 天预约量趋势。
     *
     * @param days 天数，默认 7
     * @return 趋势列表
     */
    @GetMapping("/trend")
    public Result<List<StatsDTO.TrendVO>> trend(@RequestParam(required = false) Integer days) {
        return Result.ok(adminStatisticsService.trend(days));
    }

    /**
     * 时段热度（固定 14 项）。
     *
     * @param days 统计窗口天数，默认 7
     * @return 时段热度
     */
    @GetMapping("/slot-heat")
    public Result<List<StatsDTO.SlotHeatVO>> slotHeat(@RequestParam(required = false) Integer days) {
        return Result.ok(adminStatisticsService.slotHeat(days));
    }

    /**
     * 各楼层利用率。
     *
     * @param date 日期，默认今天
     * @param slot 时段序号，默认当前时段
     * @return 楼层利用率
     */
    @GetMapping("/floor-usage")
    public Result<List<StatsDTO.FloorUsageVO>> floorUsage(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot) {
        return Result.ok(adminStatisticsService.floorUsage(date, slot));
    }

    /**
     * 预约状态分布。
     *
     * @param days 统计窗口天数，默认 7
     * @return 状态分布
     */
    @GetMapping("/status-distribution")
    public Result<List<StatsDTO.StatusDistVO>> statusDistribution(@RequestParam(required = false) Integer days) {
        return Result.ok(adminStatisticsService.statusDistribution(days));
    }
}

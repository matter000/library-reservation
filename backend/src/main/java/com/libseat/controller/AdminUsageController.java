package com.libseat.controller;

import com.libseat.common.PageResult;
import com.libseat.common.Result;
import com.libseat.dto.AdminDTO;
import com.libseat.service.AdminUsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 管理员·使用情况 / 预约记录 / 强制释放。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    /**
     * 座位使用情况（占用人只含 realName + username，无手机号）。
     *
     * @param floorId 楼层 ID
     * @param date    日期
     * @param slot    时段序号
     * @param only    过滤：all / occupied / free
     * @return 使用情况
     */
    @GetMapping("/usage")
    public Result<AdminDTO.UsageVO> usage(
            @RequestParam Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot,
            @RequestParam(required = false, defaultValue = "all") String only) {
        return Result.ok(adminUsageService.usage(floorId, date, slot, only));
    }

    /**
     * 预约记录分页查询。
     *
     * @param keyword  关键字（账号 / 姓名 / 座位号）
     * @param floorId  楼层 ID
     * @param dateFrom 起始日期
     * @param dateTo   结束日期
     * @param status   状态
     * @param page     页码
     * @param size     每页条数
     * @return 分页结果
     */
    @GetMapping("/reservations")
    public Result<PageResult<AdminDTO.AdminReservationVO>> reservations(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        AdminDTO.AdminReservationQuery query = new AdminDTO.AdminReservationQuery();
        query.setKeyword(keyword);
        query.setFloorId(floorId);
        query.setDateFrom(dateFrom);
        query.setDateTo(dateTo);
        query.setStatus(status);
        query.setPage(page);
        query.setSize(size);
        return Result.ok(adminUsageService.reservations(query));
    }

    /**
     * ★ 强制释放（原因必填 ≤100 字）。
     *
     * @param id      预约 ID
     * @param request 释放请求
     * @return 释放结果
     */
    @PostMapping("/reservations/{id}/release")
    public Result<AdminDTO.ReleaseVO> release(@PathVariable Long id,
                                              @Valid @RequestBody AdminDTO.ReleaseRequest request) {
        return Result.ok(adminUsageService.release(id, request.getReason()));
    }
}

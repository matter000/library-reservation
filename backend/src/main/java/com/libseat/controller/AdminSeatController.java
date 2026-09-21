package com.libseat.controller;

import com.libseat.common.PageResult;
import com.libseat.common.Result;
import com.libseat.dto.AdminDTO;
import com.libseat.dto.SeatDTO;
import com.libseat.service.AdminSeatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员·座位接口：分页列表 / 新增 / 编辑 / 删除 / 状态切换 / 批量生成。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminSeatController {

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_SIZE = 20;

    private final AdminSeatService adminSeatService;

    /**
     * 某楼层座位分页列表。
     *
     * @param floorId 楼层 ID
     * @param page    页码
     * @param size    每页条数
     * @param seatNo  编号关键字
     * @param status  状态筛选
     * @return 分页结果
     */
    @GetMapping("/floors/{floorId}/seats")
    public Result<PageResult<SeatDTO.SeatVO>> page(
            @PathVariable Long floorId,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size,
            @RequestParam(required = false) String seatNo,
            @RequestParam(required = false) String status) {
        int safePage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int safeSize = (size == null || size < 1) ? DEFAULT_SIZE : size;
        return Result.ok(adminSeatService.page(floorId, seatNo, status, safePage, safeSize));
    }

    /**
     * 新增座位。
     *
     * @param request 保存请求
     * @return 座位 VO
     */
    @PostMapping("/seats")
    public Result<SeatDTO.SeatVO> create(@Valid @RequestBody SeatDTO.SeatSaveRequest request) {
        return Result.ok(adminSeatService.create(request));
    }

    /**
     * 编辑座位。
     *
     * @param seatId  座位 ID
     * @param request 保存请求
     * @return 座位 VO
     */
    @PutMapping("/seats/{seatId}")
    public Result<SeatDTO.SeatVO> update(@PathVariable Long seatId,
                                         @Valid @RequestBody SeatDTO.SeatSaveRequest request) {
        return Result.ok(adminSeatService.update(seatId, request));
    }

    /**
     * 删除座位。
     *
     * @param seatId 座位 ID
     * @return 删除结果
     */
    @DeleteMapping("/seats/{seatId}")
    public Result<AdminDTO.DeleteSeatVO> delete(@PathVariable Long seatId) {
        return Result.ok(adminSeatService.delete(seatId));
    }

    /**
     * 切换座位状态。
     *
     * @param seatId  座位 ID
     * @param request 状态请求
     * @return 座位 VO
     */
    @PutMapping("/seats/{seatId}/status")
    public Result<SeatDTO.SeatVO> updateStatus(@PathVariable Long seatId,
                                               @Valid @RequestBody SeatDTO.SeatStatusRequest request) {
        return Result.ok(adminSeatService.updateStatus(seatId, request));
    }

    /**
     * ★ 批量生成座位。
     *
     * @param request 批量请求
     * @return 生成结果
     */
    @PostMapping("/seats/batch")
    public Result<SeatDTO.SeatBatchResultVO> batch(@Valid @RequestBody SeatDTO.SeatBatchRequest request) {
        return Result.ok(adminSeatService.batch(request));
    }
}

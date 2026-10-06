package com.libseat.controller;

import com.libseat.common.PageResult;
import com.libseat.common.Result;
import com.libseat.dto.ReservationDTO;
import com.libseat.security.UserContext;
import com.libseat.service.BookingDegradeGuard;
import com.libseat.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预约接口：创建 / 我的预约 / 详情 / 取消 / 签到。
 *
 * <p>所有操作强制使用 {@link UserContext} 中的 userId，不接受前端传 userId（防水平越权）。
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_SIZE = 10;

    private final ReservationService reservationService;

    private final BookingDegradeGuard degradeGuard;

    /**
     * 提交预约。
     *
     * @param request 预约请求
     * @return 创建结果
     */
    @PostMapping("")
    public Result<ReservationDTO.ReservationCreateVO> create(
            @Valid @RequestBody ReservationDTO.ReservationCreateRequest request) {
        Long userId = UserContext.getUserId();
        // PRD §13.4：过载时只有"写"这一侧排队与降级；查看余座的读接口不取许可，因此不受影响。
        degradeGuard.acquire();
        try {
            return Result.ok(reservationService.createReservation(userId, request));
        } finally {
            degradeGuard.release();
        }
    }

    /**
     * 我的预约。
     *
     * @param tab  分组：upcoming / inProgress / finished / cancelled
     * @param page 页码
     * @param size 每页条数
     * @return 分页结果
     */
    @GetMapping("/my")
    public Result<PageResult<ReservationDTO.ReservationVO>> my(
            @RequestParam(required = false, defaultValue = "upcoming") String tab,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer size) {
        Long userId = UserContext.getUserId();
        int safePage = (page == null || page < 1) ? DEFAULT_PAGE : page;
        int safeSize = (size == null || size < 1) ? DEFAULT_SIZE : size;
        return Result.ok(reservationService.myReservations(userId, tab, safePage, safeSize));
    }

    /**
     * 预约详情（非本人返回 4040）。
     *
     * @param id 预约 ID
     * @return 预约 VO
     */
    @GetMapping("/{id}")
    public Result<ReservationDTO.ReservationVO> detail(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.ok(reservationService.detail(userId, id));
    }

    /**
     * 取消预约。
     *
     * @param id 预约 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/cancel")
    public Result<ReservationDTO.ReservationActionVO> cancel(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.ok(reservationService.cancel(userId, id));
    }

    /**
     * 签到。
     *
     * @param id 预约 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/check-in")
    public Result<ReservationDTO.ReservationActionVO> checkIn(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.ok(reservationService.checkIn(userId, id));
    }

    /**
     * 主动结束使用（V2 §5.4）：用不完的时间提前释放给他人。
     *
     * @param id 预约 ID
     * @return 操作结果
     */
    @PostMapping("/{id}/finish")
    public Result<ReservationDTO.ReservationActionVO> finish(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.ok(reservationService.finish(userId, id));
    }
}

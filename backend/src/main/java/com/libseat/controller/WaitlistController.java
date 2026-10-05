package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.ReservationDTO;
import com.libseat.dto.WaitlistDTO;
import com.libseat.security.UserContext;
import com.libseat.service.WaitlistService;
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
 * 学生面·候补队列接口（DESIGN §8.3，T-V2-19）。
 *
 * <p>★ 候补不是第二条占座通道：确认时服务端重新挑座并走完整预约校验链，
 * 所以这里绝不接受前端传 seatId —— 位置是队列给的，不是用户点的。
 *
 * <p>★ userId 只从 {@link UserContext} 取：否则可以替别人确认（用别人的信用分下单）、
 * 替别人退出（把别人的位置让出去）。
 */
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    /**
     * 加入候补（仅在该时段确实满座时允许）。
     *
     * @param request 排队请求
     * @return 队列视图
     */
    @PostMapping
    public Result<WaitlistDTO.WaitlistVO> join(@Valid @RequestBody WaitlistDTO.WaitlistJoinRequest request) {
        return Result.ok(waitlistService.join(UserContext.getUserId(), UserContext.getRole(), request));
    }

    /**
     * 我的活动候补。
     *
     * @return 队列视图列表
     */
    @GetMapping("/my")
    public Result<List<WaitlistDTO.WaitlistVO>> mine() {
        return Result.ok(waitlistService.mine(UserContext.getUserId()));
    }

    /**
     * 确认候补：服务端挑一座并走完整校验链，成功即生成正常预约。
     *
     * @param id 候补记录
     * @return 下单结果
     */
    @PostMapping("/{id}/confirm")
    public Result<ReservationDTO.ReservationCreateVO> confirm(@PathVariable Long id) {
        return Result.ok(waitlistService.confirm(UserContext.getUserId(), id));
    }

    /**
     * 退出队列。
     *
     * @param id 候补记录
     * @return 队列视图
     */
    @PostMapping("/{id}/quit")
    public Result<WaitlistDTO.WaitlistVO> quit(@PathVariable Long id) {
        return Result.ok(waitlistService.quit(UserContext.getUserId(), id));
    }
}

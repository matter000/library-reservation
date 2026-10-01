package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.LeaveDTO;
import com.libseat.security.UserContext;
import com.libseat.service.LeaveService;
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
 * 用户端·暂离接口（DESIGN §5.2）。
 *
 * <p>只操作当前登录用户的预约：{@code userId} 一律取自 {@code UserContext}，不接受请求体传入。
 */
@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    /**
     * 暂离原因字典。
     *
     * @return 启用的原因列表
     */
    @GetMapping("/reasons")
    public Result<List<LeaveDTO.ReasonVO>> reasons() {
        return Result.ok(leaveService.reasons());
    }

    /**
     * 登记暂离。
     *
     * @param request 登记请求
     * @return 暂离 VO
     */
    @PostMapping("")
    public Result<LeaveDTO.LeaveVO> start(@Valid @RequestBody LeaveDTO.LeaveStartRequest request) {
        return Result.ok(leaveService.start(UserContext.getUserId(), request));
    }

    /**
     * 我已返回。
     *
     * @param leaveId 暂离 ID
     * @return 暂离 VO
     */
    @PostMapping("/{leaveId}/return")
    public Result<LeaveDTO.LeaveVO> returnBack(@PathVariable Long leaveId) {
        return Result.ok(leaveService.returnBack(UserContext.getUserId(), leaveId));
    }
}

package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.AppealDTO;
import com.libseat.enums.ViolationStatus;
import com.libseat.security.UserContext;
import com.libseat.service.ViolationService;
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
 * 馆员面·违规查询与直接撤销接口（DESIGN §12.2 F8）。
 *
 * <p>为什么和申诉放在一起交付：申诉有 24 小时窗口（PRD §9.4），窗口一过学生侧就只剩
 * 这一个入口。没有它，「超过时限的误判」就成了死案 —— 那正是「无证据不得裁定」这条
 * 规则反过来要付的代价。撤销必须填理由、必须写审计，并且和申诉成立共用同一个回补出口。
 */
@RestController
@RequestMapping("/api/staff/violations")
@RequiredArgsConstructor
public class StaffViolationController {

    private final ViolationService violationService;

    /**
     * 违规查询。
     *
     * @param userId 指定用户，省略表示全馆
     * @param status 指定状态，省略表示不限
     * @return 违规明细（新的在前）
     */
    @GetMapping
    public Result<List<AppealDTO.ViolationVO>> list(@RequestParam(required = false) Long userId,
                                                    @RequestParam(required = false) ViolationStatus status) {
        return Result.ok(violationService.listForStaff(userId, status));
    }

    /**
     * 直接撤销违规（回补分数 + 留痕）。
     *
     * @param id      违规 ID
     * @param request 撤销理由
     * @return 撤销后的违规
     */
    @PostMapping("/{id}/revoke")
    public Result<AppealDTO.ViolationVO> revoke(@PathVariable Long id,
                                                @Valid @RequestBody AppealDTO.RevokeRequest request) {
        return Result.ok(violationService.revoke(id, request.getReason(),
                UserContext.getUserId(), UserContext.getRole()));
    }
}

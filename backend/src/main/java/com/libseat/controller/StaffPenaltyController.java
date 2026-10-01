package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.PenaltyDTO;
import com.libseat.security.UserContext;
import com.libseat.service.PenaltyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 馆员面·处罚解除接口（§6.2：暂停 7 天可凭承诺书提前解除）。
 *
 * <p>「暂停至学期末」不在馆员权限内 —— 由 {@code PenaltyService} 抛 4031 明确拒绝，
 * 而不是靠菜单藏起来。
 */
@RestController
@RequestMapping("/api/staff/penalties")
@RequiredArgsConstructor
public class StaffPenaltyController {

    private final PenaltyService penaltyService;

    /**
     * 解除处罚。
     *
     * @param id      处罚 ID
     * @param request 说明
     * @return 解除结果
     */
    @PostMapping("/{id}/release")
    public Result<PenaltyDTO.PenaltyVO> release(@PathVariable Long id,
                                                @Valid @RequestBody PenaltyDTO.ReleaseRequest request) {
        return Result.ok(PenaltyDTO.PenaltyVO.of(penaltyService.release(
                id, UserContext.getUserId(), request.getNote(), false)));
    }
}

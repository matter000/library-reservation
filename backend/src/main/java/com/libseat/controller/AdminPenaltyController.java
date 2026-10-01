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
 * 系统管理面·处罚解除接口（仅 SYS_ADMIN）。
 *
 * <p>「暂停至学期末」只有这里能解（§6.2），馆员入口会拿到 4031。
 */
@RestController
@RequestMapping("/api/admin/penalties")
@RequiredArgsConstructor
public class AdminPenaltyController {

    private final PenaltyService penaltyService;

    /**
     * 解除处罚（含学期禁）。
     *
     * @param id      处罚 ID
     * @param request 说明
     * @return 解除结果
     */
    @PostMapping("/{id}/release")
    public Result<PenaltyDTO.PenaltyVO> release(@PathVariable Long id,
                                                @Valid @RequestBody PenaltyDTO.ReleaseRequest request) {
        return Result.ok(PenaltyDTO.PenaltyVO.of(penaltyService.release(
                id, UserContext.getUserId(), request.getNote(), true)));
    }
}

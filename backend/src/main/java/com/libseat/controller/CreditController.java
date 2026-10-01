package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.PenaltyDTO;
import com.libseat.security.UserContext;
import com.libseat.service.CreditService;
import com.libseat.service.PenaltyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 用户端·我的信用接口。
 *
 * <p>「我为什么被禁、什么时候恢复」必须能自己看到 —— 只给一个 4100 而不给恢复时间，
 * 等于把用户推向申诉窗口外的猜测。
 */
@RestController
@RequestMapping("/api/credit")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;

    private final PenaltyService penaltyService;

    /**
     * 当前登录用户的信用分与生效中的处罚。
     *
     * @return 信用概览
     */
    @GetMapping("/me")
    public Result<PenaltyDTO.CreditSummaryVO> me() {
        Long userId = UserContext.getUserId();
        return Result.ok(PenaltyDTO.CreditSummaryVO.builder()
                .creditScore(creditService.scoreOf(userId))
                .penalties(penaltyService.activePenalties(userId, LocalDateTime.now()).stream()
                        .map(PenaltyDTO.PenaltyVO::of)
                        .toList())
                .build());
    }
}

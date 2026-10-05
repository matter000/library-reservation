package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.RuleDTO;
import com.libseat.entity.RuleConfig;
import com.libseat.security.UserContext;
import com.libseat.service.CreditService;
import com.libseat.service.RuleConfigService;
import com.libseat.service.RuleHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统管理面·规则配置接口（仅 SYS_ADMIN，由 RolePermission 矩阵保证）。
 */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AdminRuleController {

    private final RuleConfigService ruleConfigService;

    private final CreditService creditService;

    private final RuleHistoryService ruleHistoryService;

    /**
     * ★ 账本一致性巡检：{@code sum(t_credit_log.delta)} 与 {@code t_user.credit_score} 的差异清单。
     *
     * <p>正常应恒返回空列表；非空即说明有代码绕过了 {@code CreditService} 直接改分，
     * 这是信用体系最致命的腐化方式，所以把它做成一个随时可打的接口而不是靠人查库。
     *
     * @return 差异说明（空表示一致）
     */
    @GetMapping("/consistency")
    public Result<List<String>> consistency() {
        return Result.ok(creditService.consistencyReport());
    }

    /**
     * 规则变更历史（谁在什么时候把哪个键从什么改成了什么）。
     *
     * @param rkey 只看某个键，省略表示全部
     * @return 新的在前
     */
    @GetMapping("/history")
    public Result<List<RuleDTO.RuleHistoryVO>> history(@RequestParam(required = false) String rkey) {
        return Result.ok(ruleHistoryService.history(rkey));
    }

    /**
     * 规则列表。
     *
     * @param rgroup 分组过滤，省略表示全部
     * @return 规则列表
     */
    @GetMapping
    public Result<List<RuleDTO.RuleVO>> list(@RequestParam(required = false) String rgroup) {
        List<RuleDTO.RuleVO> list = new ArrayList<>();
        for (RuleConfig rule : ruleConfigService.list(rgroup)) {
            list.add(toVO(rule));
        }
        return Result.ok(list);
    }

    /**
     * 批量更新规则值（改后立即生效，无需重启）。
     *
     * @param requests 更新项列表
     * @return 更新后的规则
     */
    @PutMapping
    public Result<List<RuleDTO.RuleVO>> update(@Valid @RequestBody List<RuleDTO.RuleUpdateRequest> requests) {
        Long actorId = UserContext.getUserId();
        List<RuleDTO.RuleVO> updated = new ArrayList<>();
        for (RuleDTO.RuleUpdateRequest request : requests) {
            updated.add(toVO(ruleConfigService.update(
                    request.getScope(), request.getScopeId(), request.getRkey(), request.getValue(), actorId)));
        }
        return Result.ok(updated);
    }

    private RuleDTO.RuleVO toVO(RuleConfig rule) {
        return RuleDTO.RuleVO.builder()
                .scope(rule.getScope())
                .scopeId(rule.getScopeId())
                .rkey(rule.getRkey())
                .rvalue(rule.getRvalue())
                .valueType(rule.getValueType())
                .rgroup(rule.getRgroup())
                .editable(rule.getEditable())
                .description(rule.getDescription())
                .build();
    }
}

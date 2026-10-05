package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.PatrolDTO;
import com.libseat.enums.PatrolAction;
import com.libseat.enums.PatrolLevel;
import com.libseat.enums.PatrolStatus;
import com.libseat.security.UserContext;
import com.libseat.service.PatrolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 馆员面·巡查任务接口（DESIGN §11 与 §12.2-F2/F3，T-V2-21）。
 *
 * <p>权限由 {@code /api/staff/} 前缀规则统一放开给馆员与系统管理员，这里不再写角色判断；
 * 但<b>处置人一律取登录态</b>：谁提醒的、谁释放的必须能追责，否则「馆员把座位放了」
 * 这件事在审计里会退化成一条无主记录。
 */
@RestController
@RequestMapping("/api/staff/patrol")
@RequiredArgsConstructor
public class StaffPatrolController {

    private final PatrolService patrolService;

    /**
     * 看板列表。
     *
     * @param openOnly 是否只看未处理（默认 true）
     * @param floorId  楼层筛选，可空
     * @param level    等级筛选，可空
     * @param status   状态筛选，仅在 {@code openOnly=false} 时有意义
     * @return 任务列表（HIGH 在前）
     */
    @GetMapping
    public Result<List<PatrolDTO.TaskVO>> board(
            @RequestParam(required = false, defaultValue = "true") boolean openOnly,
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) PatrolLevel level,
            @RequestParam(required = false) PatrolStatus status) {
        return Result.ok(patrolService.board(openOnly, floorId, level, status));
    }

    /**
     * 处置动作字典。
     *
     * <p>★ 动作名称只在 {@link PatrolAction} 一处定义：前端自己写一份中文，
     * 枚举改名时页面就会显示着「取消」而实际提交的是 {@code IGNORE}。
     *
     * @return 动作列表
     */
    @GetMapping("/actions")
    public Result<List<PatrolDTO.ActionVO>> actions() {
        List<PatrolDTO.ActionVO> actions = new ArrayList<>();
        for (PatrolAction action : PatrolAction.values()) {
            actions.add(PatrolDTO.ActionVO.builder()
                    .action(action.name())
                    .text(action.getText())
                    .targetStatus(action.getTargetStatus().name())
                    .targetStatusText(action.getTargetStatus().getText())
                    .build());
        }
        return Result.ok(actions);
    }

    /**
     * 馆员现场登记疑点（含无预约占用）。
     *
     * @param request 登记请求
     * @return 任务视图
     */
    @PostMapping
    public Result<PatrolDTO.TaskVO> register(@Valid @RequestBody PatrolDTO.RegisterRequest request) {
        return Result.ok(patrolService.registerManual(request.getSeatId(), request.getReservationId(),
                request.getNote(), request.getLevel(), request.getPhotoUrl(), UserContext.getUserId()));
    }

    /**
     * 处置一条任务。
     *
     * @param id      任务 ID
     * @param request 动作 + 必填说明（+ 可选照片）
     * @return 处置后的任务视图
     */
    @PostMapping("/{id}/handle")
    public Result<PatrolDTO.TaskVO> handle(@PathVariable Long id,
                                           @Valid @RequestBody PatrolDTO.HandleRequest request) {
        return Result.ok(patrolService.handle(id, request.getAction(), request.getNote(),
                request.getPhotoUrl(), UserContext.getUserId()));
    }
}

package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.GroupDTO;
import com.libseat.security.UserContext;
import com.libseat.service.GroupService;
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
 * 学生面·组队预约接口（DESIGN §12.1 S14-S17）。
 *
 * <p>组长签到（S18）与成员入座确认（S19）随 T-V2-16 交付；这里先把「建队到锁定前」的
 * 全部动作接完，使按桌下单可被端到端验证。
 *
 * <p>★ 所有人都从 {@link UserContext} 取，不接受前端传 userId：否则改一下请求体就能替别人建队、
 * 替别人确认，而「谁确认的」正是人数与缺席判定的依据。
 */
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    /**
     * 建队（组长＝当前登录人）。
     *
     * @param request 建队请求
     * @return 队伍视图
     */
    @PostMapping
    public Result<GroupDTO.GroupVO> create(@Valid @RequestBody GroupDTO.GroupCreateRequest request) {
        return Result.ok(groupService.create(UserContext.getUserId(), UserContext.getRole(), request));
    }

    /**
     * 我的队伍。
     *
     * @return 队伍列表
     */
    @GetMapping("/my")
    public Result<List<GroupDTO.GroupVO>> mine() {
        return Result.ok(groupService.mine(UserContext.getUserId()));
    }

    /**
     * 队伍详情。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @GetMapping("/{id}")
    public Result<GroupDTO.GroupVO> detail(@PathVariable Long id) {
        return Result.ok(groupService.detail(id, UserContext.getUserId()));
    }

    /**
     * 组长按账号邀请成员。
     *
     * @param id      队伍 ID
     * @param request 被邀请账号
     * @return 队伍视图
     */
    @PostMapping("/{id}/invite")
    public Result<GroupDTO.GroupVO> invite(@PathVariable Long id,
                                           @Valid @RequestBody GroupDTO.InviteRequest request) {
        return Result.ok(groupService.invite(id, UserContext.getUserId(), request.getUsername()));
    }

    /**
     * 用邀请码加入（登记为待确认）。
     *
     * @param request 邀请码
     * @return 队伍视图
     */
    @PostMapping("/join")
    public Result<GroupDTO.GroupVO> join(@Valid @RequestBody GroupDTO.JoinRequest request) {
        return Result.ok(groupService.join(request.getInviteCode(), UserContext.getUserId()));
    }

    /**
     * 成员确认。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @PostMapping("/{id}/confirm")
    public Result<GroupDTO.GroupVO> confirm(@PathVariable Long id) {
        return Result.ok(groupService.confirm(id, UserContext.getUserId()));
    }

    /**
     * 成员退出（仅未锁定时允许）。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @PostMapping("/{id}/quit")
    public Result<GroupDTO.GroupVO> quit(@PathVariable Long id) {
        return Result.ok(groupService.quit(id, UserContext.getUserId()));
    }

    /**
     * 组长取消队伍。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @PostMapping("/{id}/cancel")
    public Result<GroupDTO.GroupVO> cancel(@PathVariable Long id) {
        return Result.ok(groupService.cancel(id, UserContext.getUserId()));
    }

    /**
     * 组长签到 = 全队签到（S18）。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @PostMapping("/{id}/check-in")
    public Result<GroupDTO.GroupVO> checkInByLeader(@PathVariable Long id) {
        return Result.ok(groupService.checkInByLeader(id, UserContext.getUserId()));
    }

    /**
     * 成员现场确认入座（S19）。
     *
     * @param id 队伍 ID
     * @return 队伍视图
     */
    @PostMapping("/{id}/attend")
    public Result<GroupDTO.GroupVO> attend(@PathVariable Long id) {
        return Result.ok(groupService.confirmAttend(id, UserContext.getUserId()));
    }
}

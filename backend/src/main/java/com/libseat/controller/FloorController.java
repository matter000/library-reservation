package com.libseat.controller;

import com.libseat.common.BizConstants;
import com.libseat.common.Result;
import com.libseat.dto.FloorDTO;
import com.libseat.dto.SeatDTO;
import com.libseat.service.FloorService;
import com.libseat.service.RuleConfigService;
import com.libseat.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户端楼层与平面图接口。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FloorController {

    private final FloorService floorService;

    private final RuleConfigService ruleConfigService;

    /**
     * 楼层列表（含空闲数与占用率）。
     *
     * @param date 日期，默认今天
     * @param slot 时段序号，默认当前时段
     * @return 楼层列表
     */
    @GetMapping("/floors")
    public Result<List<FloorDTO.FloorVO>> listFloors(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot) {
        return Result.ok(floorService.listFloors(date, slot));
    }

    /**
     * 楼层详情。
     *
     * @param floorId 楼层 ID
     * @param date    日期，默认今天
     * @param slot    时段序号，默认当前时段
     * @return 楼层详情
     */
    @GetMapping("/floors/{floorId}")
    public Result<FloorDTO.FloorVO> floorDetail(
            @PathVariable Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot) {
        return Result.ok(floorService.getFloor(floorId, date, slot));
    }

    /**
     * ★ 座位平面图（响应不含任何占用人身份）。
     *
     * <p>{@code zoneId} 可选：给定则只返回该分区座位，并附带该分区对当前角色的有效规则。
     *
     * @param floorId 楼层 ID
     * @param date    日期，默认今天
     * @param slot    时段序号，默认当前时段
     * @param zoneId  分区 ID，省略表示整层
     * @return 平面图
     */
    @GetMapping("/seats/map")
    public Result<SeatDTO.SeatMapVO> seatMap(
            @RequestParam Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot,
            @RequestParam(required = false) Long zoneId) {
        return Result.ok(floorService.getSeatMap(floorId, date, slot, zoneId, UserContext.getRole()));
    }

    /**
     * 时段选项（14 项）。
     *
     * @param date 日期，默认今天
     * @return 时段选项
     */
    @GetMapping("/config/slots")
    public Result<List<FloorDTO.SlotOptionVO>> slots(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return Result.ok(floorService.getSlotOptions(date));
    }

    /**
     * 预约表单配置（可约天数 / 可选时长 / 签到窗口），取自规则中心。
     *
     * @return 配置
     */
    @GetMapping("/config/booking")
    public Result<FloorDTO.BookingConfigVO> bookingConfig() {
        return Result.ok(FloorDTO.BookingConfigVO.builder()
                .advanceDays(ruleConfigService.advanceDays())
                .allowedDurations(ruleConfigService.allowedDurations())
                .defaultDuration(ruleConfigService.defaultDuration())
                .checkInBeforeMin(ruleConfigService.checkInBeforeMin())
                .checkInAfterMin(ruleConfigService.checkInAfterMin())
                // ★ 组队口径也走这里：前端要写「开始前 30 分钟锁定」就必须从服务端取，
                //   两处各写一份数字，改值后页面就成了假说明。
                .groupLockBeforeMin(BizConstants.GROUP_LOCK_BEFORE_MIN)
                .groupInviteTtlMin(BizConstants.GROUP_INVITE_TTL_MIN)
                .groupMinMembers(BizConstants.GROUP_MIN_MEMBERS)
                .groupMaxMembers(BizConstants.GROUP_MAX_MEMBERS)
                .groupAttendGraceMin(BizConstants.GROUP_ATTEND_GRACE_MIN)
                // 候补窗口与名额是规则中心的键（管理员可调），不是一级常量
                .waitlistOfferMin(ruleConfigService.waitlistOfferMin())
                .waitlistMaxActive(ruleConfigService.waitlistMaxActive())
                .build());
    }
}

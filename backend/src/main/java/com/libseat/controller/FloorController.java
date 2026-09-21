package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.FloorDTO;
import com.libseat.dto.SeatDTO;
import com.libseat.service.FloorService;
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
     * @param floorId 楼层 ID
     * @param date    日期，默认今天
     * @param slot    时段序号，默认当前时段
     * @return 平面图
     */
    @GetMapping("/seats/map")
    public Result<SeatDTO.SeatMapVO> seatMap(
            @RequestParam Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot) {
        return Result.ok(floorService.getSeatMap(floorId, date, slot));
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
}

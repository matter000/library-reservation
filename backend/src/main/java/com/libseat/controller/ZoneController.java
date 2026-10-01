package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.ZoneDTO;
import com.libseat.security.UserContext;
import com.libseat.service.ZoneService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户端分区接口：余量卡片列表（响应不含任何占用人身份）。
 *
 * <p>可见性按令牌里的角色过滤，不接受角色入参。
 */
@RestController
@RequestMapping("/api/zones")
@RequiredArgsConstructor
public class ZoneController {

    private final ZoneService zoneService;

    /**
     * 分区列表。
     *
     * @param floorId 楼层 ID，省略表示全部楼层
     * @param date    日期，默认今天
     * @param slot    时段序号，默认当前时段
     * @return 分区列表
     */
    @GetMapping
    public Result<List<ZoneDTO.ZoneVO>> list(
            @RequestParam(required = false) Long floorId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam(required = false) Integer slot) {
        return Result.ok(zoneService.listForUser(floorId, date, slot, UserContext.getRole()));
    }
}

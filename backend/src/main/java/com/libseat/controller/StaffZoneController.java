package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.ZoneDTO;
import com.libseat.service.ZoneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 馆员作业面·分区管理（LIBRARIAN / SYS_ADMIN，由 RolePermission 矩阵保证）。
 */
@RestController
@RequestMapping("/api/staff/zones")
@RequiredArgsConstructor
public class StaffZoneController {

    private final ZoneService zoneService;

    /**
     * 按楼层列分区（含关闭分区，不做可见性过滤）。
     *
     * @param floorId 楼层 ID
     * @return 分区列表
     */
    @GetMapping
    public Result<List<ZoneDTO.ZoneVO>> list(@RequestParam Long floorId) {
        return Result.ok(zoneService.listForAdmin(floorId));
    }

    /**
     * 新增分区。
     *
     * @param request 分区内容
     * @return 分区 VO
     */
    @PostMapping
    public Result<ZoneDTO.ZoneVO> create(@Valid @RequestBody ZoneDTO.ZoneSaveRequest request) {
        return Result.ok(zoneService.create(request));
    }

    /**
     * 编辑分区。
     *
     * @param zoneId  分区 ID
     * @param request 分区内容
     * @return 分区 VO
     */
    @PutMapping("/{zoneId}")
    public Result<ZoneDTO.ZoneVO> update(@PathVariable Long zoneId,
                                         @Valid @RequestBody ZoneDTO.ZoneSaveRequest request) {
        return Result.ok(zoneService.update(zoneId, request));
    }

    /**
     * 切换分区开放状态。
     *
     * @param zoneId  分区 ID
     * @param request 状态请求
     * @return 分区 VO
     */
    @PutMapping("/{zoneId}/status")
    public Result<ZoneDTO.ZoneVO> toggleStatus(@PathVariable Long zoneId,
                                               @Valid @RequestBody ZoneDTO.ZoneStatusRequest request) {
        return Result.ok(zoneService.toggleStatus(zoneId, request.getStatus()));
    }

    /**
     * 删除分区（存在有效预约时 4109）。
     *
     * @param zoneId 分区 ID
     * @return 空结果
     */
    @DeleteMapping("/{zoneId}")
    public Result<Void> delete(@PathVariable Long zoneId) {
        zoneService.delete(zoneId);
        return Result.ok(null);
    }
}

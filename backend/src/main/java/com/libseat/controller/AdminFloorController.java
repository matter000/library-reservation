package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.AdminDTO;
import com.libseat.dto.FloorDTO;
import com.libseat.service.AdminFloorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员·楼层接口（路径 /api/admin/floors/**，拦截器强制 ADMIN）。
 */
@RestController
@RequestMapping("/api/admin/floors")
@RequiredArgsConstructor
public class AdminFloorController {

    private final AdminFloorService adminFloorService;

    /**
     * 楼层管理列表。
     *
     * @return 楼层列表
     */
    @GetMapping
    public Result<List<FloorDTO.FloorVO>> list() {
        return Result.ok(adminFloorService.list());
    }

    /**
     * 新增楼层。
     *
     * @param request 保存请求
     * @return 楼层 VO
     */
    @PostMapping
    public Result<FloorDTO.FloorVO> create(@Valid @RequestBody FloorDTO.FloorSaveRequest request) {
        return Result.ok(adminFloorService.create(request));
    }

    /**
     * 编辑楼层。
     *
     * @param floorId 楼层 ID
     * @param request 保存请求
     * @return 楼层 VO
     */
    @PutMapping("/{floorId}")
    public Result<FloorDTO.FloorVO> update(@PathVariable Long floorId,
                                           @Valid @RequestBody FloorDTO.FloorSaveRequest request) {
        return Result.ok(adminFloorService.update(floorId, request));
    }

    /**
     * 删除楼层。
     *
     * @param floorId 楼层 ID
     * @return 删除结果
     */
    @DeleteMapping("/{floorId}")
    public Result<AdminDTO.DeleteFloorVO> delete(@PathVariable Long floorId) {
        return Result.ok(adminFloorService.delete(floorId));
    }

    /**
     * 切换楼层开放 / 关闭状态。
     *
     * @param floorId 楼层 ID
     * @param request 状态请求
     * @return 楼层 VO
     */
    @PutMapping("/{floorId}/status")
    public Result<FloorDTO.FloorVO> updateStatus(@PathVariable Long floorId,
                                                 @Valid @RequestBody FloorDTO.FloorStatusRequest request) {
        return Result.ok(adminFloorService.updateStatus(floorId, request));
    }
}

package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.TableDTO;
import com.libseat.service.TableService;
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
 * 馆员作业面·桌位管理（LIBRARIAN / SYS_ADMIN，由 RolePermission 矩阵保证）。
 */
@RestController
@RequestMapping("/api/staff/tables")
@RequiredArgsConstructor
public class StaffTableController {

    private final TableService tableService;

    /**
     * 按分区列桌位。
     *
     * @param zoneId 分区 ID
     * @return 桌位列表
     */
    @GetMapping
    public Result<List<TableDTO.TableVO>> list(@RequestParam Long zoneId) {
        return Result.ok(tableService.listByZone(zoneId));
    }

    /**
     * 新增桌位。
     *
     * @param request 桌位内容
     * @return 桌位 VO
     */
    @PostMapping
    public Result<TableDTO.TableVO> create(@Valid @RequestBody TableDTO.TableSaveRequest request) {
        return Result.ok(tableService.create(request));
    }

    /**
     * 编辑桌位。
     *
     * @param tableId 桌位 ID
     * @param request 桌位内容
     * @return 桌位 VO
     */
    @PutMapping("/{tableId}")
    public Result<TableDTO.TableVO> update(@PathVariable Long tableId,
                                           @Valid @RequestBody TableDTO.TableSaveRequest request) {
        return Result.ok(tableService.update(tableId, request));
    }

    /**
     * 删除桌位（仍挂着座位时 4109）。
     *
     * @param tableId 桌位 ID
     * @return 空结果
     */
    @DeleteMapping("/{tableId}")
    public Result<Void> delete(@PathVariable Long tableId) {
        tableService.delete(tableId);
        return Result.ok(null);
    }

    /**
     * ★ 按桌生成相邻座位。
     *
     * @param tableId 桌位 ID
     * @param request 生成请求，count 省略则按声明座位数
     * @return 桌位 VO（含生成后的座位清单）
     */
    @PostMapping("/{tableId}/seats/generate")
    public Result<TableDTO.TableVO> generate(@PathVariable Long tableId,
                                             @Valid @RequestBody(required = false) TableDTO.TableGenerateRequest request) {
        return Result.ok(tableService.generateSeats(tableId, request == null ? null : request.getCount()));
    }
}

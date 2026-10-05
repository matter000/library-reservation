package com.libseat.controller;

import com.libseat.common.BizConstants;
import com.libseat.service.AuditService;
import com.libseat.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运营台账导出（DESIGN §12 A7 / TASKS-V2 T-V2-22）。
 *
 * <p>★ 导出必须留审计：一份 CSV 就是成批的个人信息（谁在几号座位待到几点、谁被扣了几分）。
 * 「谁在什么时候带走了哪批数据」是这类导出唯一可追溯的记录。
 * 而且只能在控制器里写：{@code ExportService} 在 {@code readOnly} 事务里，
 * 从只读事务里插审计会失败并被 {@code AuditService} 的兜底吞成一条 error 日志。
 *
 * <p>★ 只有系统管理员能调（{@code /api/admin/} 前缀规则）：馆员看的是大屏与看板，
 * 拿走整本台账是管理职责，不是楼层巡查的一部分。
 */
@RestController
@RequestMapping("/api/admin/export")
@RequiredArgsConstructor
public class AdminExportController {

    private final ExportService exportService;

    private final AuditService auditService;

    /**
     * 导出台账。
     *
     * @param type     reservation / violation / patrol / credit
     * @param dateFrom 起始日期（仅预约台账用，缺省今天）
     * @param dateTo   结束日期（仅预约台账用，缺省与起始同一天）
     * @return CSV 字节（UTF-8 带 BOM，Excel 直接双击可开）
     */
    @GetMapping("/{type}")
    public ResponseEntity<byte[]> export(
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate dateTo) {
        LocalDateTime now = LocalDateTime.now();
        ExportService.ExportFile file = exportService.export(type, dateFrom, dateTo, now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("fileName", file.fileName());
        detail.put("rows", file.rows());
        detail.put("dateFrom", dateFrom == null ? null : BizConstants.formatDate(dateFrom));
        detail.put("dateTo", dateTo == null ? null : BizConstants.formatDate(dateTo));
        auditService.record(AuditService.ACTION_DATA_EXPORT, "EXPORT", type, detail);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .body(file.content());
    }
}

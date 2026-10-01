package com.libseat.controller;

import com.libseat.common.PageResult;
import com.libseat.common.Result;
import com.libseat.dto.AuditDTO;
import com.libseat.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 系统管理面·操作审计查询（仅 SYS_ADMIN）。
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;

    /**
     * 审计日志分页查询。
     *
     * @param from  起始时间（yyyy-MM-dd HH:mm:ss），可空
     * @param to    结束时间，可空
     * @param action 动作码，可空
     * @param page  页码，默认 1
     * @param size  每页条数，默认 20
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<AuditDTO.AuditVO>> list(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "20") Integer size) {
        return Result.ok(auditService.search(from, to, action, page, size));
    }
}

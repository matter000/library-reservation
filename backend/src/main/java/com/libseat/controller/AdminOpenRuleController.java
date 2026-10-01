package com.libseat.controller;

import com.libseat.common.Result;
import com.libseat.dto.OpenRuleDTO;
import com.libseat.service.OpenRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 系统管理端·开放时间（仅 SYS_ADMIN，由 RolePermission 矩阵保证）。
 *
 * <p>档案定义「某一日型什么时候开门」，日历覆盖「某一天具体怎样」，
 * {@code /day-rule} 用来自检某一天最终生效的是哪一个。
 */
@RestController
@RequestMapping("/api/admin/open")
@RequiredArgsConstructor
public class AdminOpenRuleController {

    private final OpenRuleService openRuleService;

    /**
     * 开放档案列表。
     *
     * @return 档案列表
     */
    @GetMapping("/profiles")
    public Result<List<OpenRuleDTO.ProfileVO>> profiles() {
        return Result.ok(openRuleService.listProfiles());
    }

    /**
     * 新增或编辑开放档案。
     *
     * @param request 档案内容
     * @return 保存结果
     */
    @PostMapping("/profiles")
    public Result<OpenRuleDTO.ProfileVO> saveProfile(@Valid @RequestBody OpenRuleDTO.ProfileSaveRequest request) {
        return Result.ok(openRuleService.saveProfile(request));
    }

    /**
     * 日历覆盖列表。
     *
     * @param from 起始日期，默认今天
     * @param to   结束日期，默认 from+30 天
     * @return 覆盖列表
     */
    @GetMapping("/calendar")
    public Result<List<OpenRuleDTO.CalendarVO>> calendar(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to) {
        return Result.ok(openRuleService.listCalendar(from, to));
    }

    /**
     * 新增或编辑日历覆盖。
     *
     * @param request 覆盖内容
     * @return 保存结果
     */
    @PostMapping("/calendar")
    public Result<OpenRuleDTO.CalendarVO> saveCalendar(@Valid @RequestBody OpenRuleDTO.CalendarSaveRequest request) {
        return Result.ok(openRuleService.saveCalendar(request));
    }

    /**
     * 删除日历覆盖（该日回到日型档案）。
     *
     * @param id 记录 ID
     * @return 空结果
     */
    @DeleteMapping("/calendar/{id}")
    public Result<Void> deleteCalendar(@PathVariable Long id) {
        openRuleService.deleteCalendar(id);
        return Result.ok(null);
    }

    /**
     * 查看某一天最终生效的开放规则及来源。
     *
     * @param date 日期，默认今天
     * @return 生效规则
     */
    @GetMapping("/day-rule")
    public Result<OpenRuleDTO.DayRuleVO> dayRule(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        return Result.ok(openRuleService.dayRuleView(date));
    }
}

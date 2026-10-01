package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.OpenRuleDTO;
import com.libseat.entity.OpenCalendar;
import com.libseat.entity.OpenProfile;
import com.libseat.entity.PeakWindow;
import com.libseat.enums.DayType;
import com.libseat.repository.OpenCalendarRepository;
import com.libseat.repository.OpenProfileRepository;
import com.libseat.repository.PeakWindowRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放规则解析：把「某一天」折叠成一份 {@link DayRule}。
 *
 * <p>解析链：日历覆盖 → 日型档案 → 全局默认（{@code BizConstants} 常量）。
 * 结果按日期做 LRU 缓存（当天数据极少变化），管理面写入后必须调用 {@link #invalidate()}，
 * 否则改了开放时间最长要等缓存过期才生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenRuleService {

    /** 缓存容量：足够覆盖一个学期的排班窗口。 */
    private static final int CACHE_CAPACITY = 512;

    /** 星期文案（索引 0 = 周一）。 */
    private static final String[] WEEKDAY_TEXT = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final OpenProfileRepository openProfileRepository;

    private final OpenCalendarRepository openCalendarRepository;

    private final PeakWindowRepository peakWindowRepository;

    private final AuditService auditService;

    private final Deque<LocalDate> order = new ArrayDeque<>();

    private final Map<LocalDate, DayRule> cache = new HashMap<>();

    /**
     * 解析某天的生效规则。
     *
     * @param date 日期
     * @return 日规则（非空）
     */
    @Transactional(readOnly = true)
    public DayRule resolve(LocalDate date) {
        DayRule cached = peek(date);
        if (cached != null) {
            return cached;
        }
        DayRule rule = load(date);
        remember(date, rule);
        return rule;
    }

    /**
     * 清空缓存（管理面改动后调用）。
     */
    public void invalidate() {
        synchronized (this) {
            cache.clear();
            order.clear();
        }
        log.info("开放规则缓存已清空");
    }

    /**
     * 某天是否处于临时闭馆。
     *
     * @param date 日期
     * @return 是否闭馆
     */
    public boolean isClosed(LocalDate date) {
        return resolve(date).isClosed();
    }

    /**
     * 取某天的结束边界（开区间）。
     *
     * @param date 日期
     * @return 结束时段的开区间序号
     */
    public int endSlotOf(LocalDate date) {
        return resolve(date).getOpenEnd();
    }

    // ==================== 管理面读写 ====================

    /**
     * 列出全部开放档案。
     *
     * @return 档案列表
     */
    @Transactional(readOnly = true)
    public List<OpenRuleDTO.ProfileVO> listProfiles() {
        return openProfileRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(OpenProfile::getDayType))
                .map(this::toProfileVO)
                .toList();
    }

    /**
     * 新增或编辑开放档案（按日型唯一）。
     *
     * @param request 档案内容
     * @return 保存后的档案
     */
    @Transactional
    public OpenRuleDTO.ProfileVO saveProfile(OpenRuleDTO.ProfileSaveRequest request) {
        DayType dayType = parse(DayType.class, request.getDayType(), "日型");
        if (request.getOpenEnd() <= request.getOpenStart()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "开放结束时段的开区间边界必须大于起始时段");
        }
        OpenProfile profile = openProfileRepository.findByDayType(dayType).orElseGet(OpenProfile::new);
        profile.setDayType(dayType);
        profile.setName(request.getName().trim());
        profile.setOpenStart(request.getOpenStart());
        profile.setOpenEnd(request.getOpenEnd());
        profile.setClosed(Boolean.TRUE.equals(request.getClosed()));
        profile.setEnabled(request.getEnabled() == null || request.getEnabled());
        profile.setRemark(blankToNull(request.getRemark()));
        OpenProfile saved = openProfileRepository.save(profile);
        invalidate();
        auditService.record(AuditService.ACTION_OPEN_RULE_SAVE, "OPEN_PROFILE", saved.getId(),
                Map.of("dayType", dayType.name(), "openStart", saved.getOpenStart(),
                        "openEnd", saved.getOpenEnd(), "closed", saved.getClosed()));
        return toProfileVO(saved);
    }

    /**
     * 列出日期区间内的日历覆盖。
     *
     * @param from 起始日期，默认今天
     * @param to   结束日期，默认 from+30 天
     * @return 覆盖列表
     */
    @Transactional(readOnly = true)
    public List<OpenRuleDTO.CalendarVO> listCalendar(LocalDate from, LocalDate to) {
        LocalDate start = (from == null) ? LocalDate.now() : from;
        LocalDate end = (to == null) ? start.plusDays(30) : to;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "结束日期不得早于起始日期");
        }
        return openCalendarRepository.findByOpenDateBetweenOrderByOpenDateAsc(start, end).stream()
                .map(this::toCalendarVO)
                .toList();
    }

    /**
     * 新增或编辑日历覆盖（按日期唯一）。
     *
     * @param request 覆盖内容
     * @return 保存后的覆盖
     */
    @Transactional
    public OpenRuleDTO.CalendarVO saveCalendar(OpenRuleDTO.CalendarSaveRequest request) {
        LocalDate date;
        try {
            date = LocalDate.parse(request.getDate().trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "日期格式应为 " + BizConstants.DATE_PATTERN);
        }
        if (request.getOpenStart() != null && request.getOpenEnd() != null
                && request.getOpenEnd() <= request.getOpenStart()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "开放结束时段的开区间边界必须大于起始时段");
        }
        OpenCalendar calendar = openCalendarRepository.findByOpenDate(date).orElseGet(OpenCalendar::new);
        calendar.setOpenDate(date);
        calendar.setDayType(request.getDayType() == null || request.getDayType().isBlank()
                ? null : parse(DayType.class, request.getDayType(), "日型"));
        calendar.setOpenStart(request.getOpenStart());
        calendar.setOpenEnd(request.getOpenEnd());
        calendar.setClosed(Boolean.TRUE.equals(request.getClosed()));
        calendar.setRemark(blankToNull(request.getRemark()));
        OpenCalendar saved = openCalendarRepository.save(calendar);
        invalidate();
        auditService.record(AuditService.ACTION_OPEN_RULE_SAVE, "OPEN_CALENDAR", saved.getId(),
                Map.of("date", date.toString(), "closed", saved.getClosed()));
        return toCalendarVO(saved);
    }

    /**
     * 删除日历覆盖（删除后该日回到日型档案）。
     *
     * @param id 记录 ID
     */
    @Transactional
    public void deleteCalendar(Long id) {
        OpenCalendar calendar = openCalendarRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "日历覆盖不存在"));
        openCalendarRepository.delete(calendar);
        invalidate();
        auditService.record(AuditService.ACTION_OPEN_RULE_DELETE, "OPEN_CALENDAR", id,
                Map.of("date", calendar.getOpenDate().toString()));
    }

    /**
     * 查看某一天最终生效的规则，并说明来源（管理面自检：改了日历到底生效没有）。
     *
     * @param date 日期
     * @return 生效规则
     */
    @Transactional(readOnly = true)
    public OpenRuleDTO.DayRuleVO dayRuleView(LocalDate date) {
        DayRule rule = resolve(date == null ? LocalDate.now() : date);
        return OpenRuleDTO.DayRuleVO.builder()
                .date(BizConstants.formatDate(rule.getDate()))
                .dayType(rule.getDayType().name())
                .openStart(rule.getOpenStart())
                .openEnd(rule.getOpenEnd())
                .startTime(BizConstants.slotText(rule.getOpenStart()))
                .endTime(BizConstants.slotText(rule.getOpenEnd()))
                .closed(rule.isClosed())
                .slotCount(rule.slotCount())
                .source(rule.getCalendarRemark() != null
                        ? "日历覆盖：" + rule.getCalendarRemark()
                        : "开放档案：" + (rule.getProfileName() == null ? "系统默认" : rule.getProfileName()))
                .peaks(rule.getPeaks().stream().map(PeakWindow::getName).toList())
                .build();
    }

    private OpenRuleDTO.ProfileVO toProfileVO(OpenProfile profile) {
        boolean closed = Boolean.TRUE.equals(profile.getClosed());
        return OpenRuleDTO.ProfileVO.builder()
                .id(profile.getId())
                .dayType(profile.getDayType().name())
                .dayTypeText(profile.getDayType().getText())
                .name(profile.getName())
                .openStart(profile.getOpenStart())
                .openEnd(profile.getOpenEnd())
                .startTime(BizConstants.slotText(profile.getOpenStart()))
                .endTime(BizConstants.slotText(profile.getOpenEnd()))
                .closed(closed)
                .enabled(profile.getEnabled())
                .slotCount(closed ? 0 : profile.getOpenEnd() - profile.getOpenStart())
                .remark(profile.getRemark())
                .build();
    }

    private OpenRuleDTO.CalendarVO toCalendarVO(OpenCalendar calendar) {
        return OpenRuleDTO.CalendarVO.builder()
                .id(calendar.getId())
                .date(BizConstants.formatDate(calendar.getOpenDate()))
                .weekdayText(WEEKDAY_TEXT[calendar.getOpenDate().getDayOfWeek().getValue() - 1])
                .dayType(calendar.getDayType() == null ? null : calendar.getDayType().name())
                .openStart(calendar.getOpenStart())
                .openEnd(calendar.getOpenEnd())
                .startTime(calendar.getOpenStart() == null ? null
                        : BizConstants.slotText(calendar.getOpenStart()))
                .endTime(calendar.getOpenEnd() == null ? null
                        : BizConstants.slotText(calendar.getOpenEnd()))
                .closed(calendar.getClosed())
                .remark(calendar.getRemark())
                .build();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    field + "取值非法：" + value);
        }
    }

    private DayRule peek(LocalDate date) {
        synchronized (this) {
            return cache.get(date);
        }
    }

    private void remember(LocalDate date, DayRule rule) {
        synchronized (this) {
            cache.put(date, rule);
            order.addLast(date);
            while (order.size() > CACHE_CAPACITY) {
                cache.remove(order.removeFirst());
            }
        }
    }

    private DayRule load(LocalDate date) {
        OpenCalendar override = openCalendarRepository.findByOpenDate(date).orElse(null);
        DayType dayType = (override != null && override.getDayType() != null)
                ? override.getDayType() : DayType.ofDate(date);
        OpenProfile profile = openProfileRepository.findFirstByDayTypeAndEnabledTrue(dayType).orElse(null);

        int start = firstNonNull(
                override == null ? null : override.getOpenStart(),
                profile == null ? null : profile.getOpenStart(),
                BizConstants.DEFAULT_SLOT_START);
        int end = firstNonNull(
                override == null ? null : override.getOpenEnd(),
                profile == null ? null : profile.getOpenEnd(),
                BizConstants.DEFAULT_SLOT_END);
        // 闭馆是「或」语义：档案说这天型不开门，或日历把这天单独关掉，都算关
        boolean closed = (profile != null && Boolean.TRUE.equals(profile.getClosed()))
                || (override != null && Boolean.TRUE.equals(override.getClosed()));

        List<PeakWindow> peaks = peakWindowRepository.findByEnabledTrue().stream()
                .filter(p -> hitsDayType(p.getDayTypes(), dayType))
                .filter(p -> p.getStartSlot() < p.getEndSlot())
                .toList();

        return new DayRule(date, dayType, start, end, closed, peaks,
                override != null ? override.getRemark() : null,
                profile != null ? profile.getName() : null);
    }

    /** 日型 CSV 命中判断（CSV 的唯一解析处）。 */
    private static boolean hitsDayType(String csv, DayType dayType) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String token : csv.split(",")) {
            if (token.trim().equalsIgnoreCase(dayType.name())) {
                return true;
            }
        }
        return false;
    }

    private static int firstNonNull(Integer first, Integer second, int fallback) {
        if (first != null) {
            return first;
        }
        return second != null ? second : fallback;
    }

    /**
     * 某天的生效开放规则（不可变值对象）。
     */
    @Getter
    @RequiredArgsConstructor
    public static final class DayRule {

        /** 日期。 */
        private final LocalDate date;

        /** 日型。 */
        private final DayType dayType;

        /** 起始时段序号（含）。 */
        private final int openStart;

        /** 结束时段的开区间边界。 */
        private final int openEnd;

        /** 是否闭馆。 */
        private final boolean closed;

        /** 命中该日型的高峰窗口。 */
        private final List<PeakWindow> peaks;

        /** 日历覆盖的备注。 */
        private final String calendarRemark;

        /** 档案名称。 */
        private final String profileName;

        /** 当天可预约的时段格数（闭馆为 0）。 */
        public int slotCount() {
            return closed ? 0 : Math.max(0, openEnd - openStart);
        }

        /**
         * 某时段是否落在开放窗口内。
         *
         * @param slot 时段序号
         * @return 是否开放
         */
        public boolean isOpenSlot(int slot) {
            return !closed && slot >= openStart && slot < openEnd;
        }

        /**
         * 取覆盖某时段的高峰窗口，多个时取压缩上限最严的那个。
         *
         * @param slot 时段序号
         * @return 高峰窗口，未命中返回 null
         */
        public PeakWindow peakOf(int slot) {
            PeakWindow strictest = null;
            for (PeakWindow peak : peaks) {
                if (slot >= peak.getStartSlot() && slot < peak.getEndSlot()) {
                    if (strictest == null || stricter(peak, strictest)) {
                        strictest = peak;
                    }
                }
            }
            return strictest;
        }

        private static boolean stricter(PeakWindow candidate, PeakWindow current) {
            Integer a = candidate.getMaxDurationMin();
            Integer b = current.getMaxDurationMin();
            if (a == null) {
                return false;
            }
            return b == null || a < b;
        }
    }
}

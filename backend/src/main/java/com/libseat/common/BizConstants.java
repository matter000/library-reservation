package com.libseat.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 全局业务常量与时段工具方法（DESIGN-V2 §4）。
 *
 * <p><b>时段编码唯一口径</b>：内部格长固定 30 分钟，{@code slot = (hour*60 + minute) / 30}，
 * {@code end_slot} 为开区间。{@code duration} 仍然是<b>小时</b>（用户可选时长的语义不变），
 * 因此任何「起始时段 + 时长」的运算都必须先经 {@link #slotsOf(int)} 换算单位，
 * 不能像 V1 那样把小时直接加在时段号上（V1 恰好 1 格 = 1 小时才没暴露这个单位混用）。
 *
 * <p>默认开放窗口 08:00–22:00 → 起始时段 16~43，结束边界 44，共 28 格；
 * 实际窗口由开放日历（{@code t_open_profile} / {@code t_open_calendar}）按日型覆盖。
 */
public final class BizConstants {

    /** 每个时段的分钟数（内部格长，固定 30 分钟）。 */
    public static final int SLOT_MINUTES = 30;

    /** 每小时的时段格数。 */
    public static final int SLOTS_PER_HOUR = 60 / SLOT_MINUTES;

    /** 开放结束小时（开区间）。 */
    public static final int OPEN_HOUR_END = 22;

    /** 默认开放起始时段序号（08:00）。 */
    public static final int DEFAULT_SLOT_START = 8 * SLOTS_PER_HOUR;

    /** 默认开放结束时段的开区间边界（22:00）。 */
    public static final int DEFAULT_SLOT_END = OPEN_HOUR_END * SLOTS_PER_HOUR;

    /** 时段总数（默认窗口内可预约的起始时段数）。 */
    public static final int SLOT_COUNT = DEFAULT_SLOT_END - DEFAULT_SLOT_START;

    /** slot_index 最小值（可作为起始时段的最小格）。 */
    public static final int SLOT_INDEX_MIN = DEFAULT_SLOT_START;

    /** slot_index 最大值（最后一格的起始时段，即 21:30）。 */
    public static final int SLOT_INDEX_MAX = DEFAULT_SLOT_END - 1;

    /** 默认时长（小时）。 */
    public static final int DEFAULT_DURATION = 2;

    /** 可选时长（小时）；上限即本列表最大值 4 小时。 */
    public static final List<Integer> ALLOWED_DURATIONS = List.of(1, 2, 3, 4);

    /** 可预约窗口天数（今天 ~ 今天+6）。 */
    public static final int ADVANCE_DAYS = 7;

    /** 开始前可签到分钟数。 */
    public static final int CHECK_IN_BEFORE_MIN = 15;

    /** 开始后签到宽限分钟数。 */
    public static final int CHECK_IN_AFTER_MIN = 15;

    /** 日期格式。 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 日期时间格式。 */
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 手机号脱敏保留的前缀长度。 */
    public static final int PHONE_MASK_PREFIX = 3;

    /** 手机号脱敏保留的后缀长度。 */
    public static final int PHONE_MASK_SUFFIX = 4;

    /** 日期格式化器。 */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);

    /** 日期时间格式化器。 */
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_PATTERN);

    private BizConstants() {
        // 工具类禁止实例化
    }

    /**
     * 小时时长 → 时段格数。跨单位运算的唯一入口。
     *
     * @param durationHours 时长（小时）
     * @return 格数
     */
    public static int slotsOf(int durationHours) {
        return durationHours * SLOTS_PER_HOUR;
    }

    /**
     * 时段格数 → 小时（用于把库里的 end_slot - start_slot 还原为时长语义）。
     *
     * <p>与 {@link #slotsOf(int)} 成对出现：写库时小时换算为格，读库展示时格还原为小时。
     * 任何一处漏掉换算，就会把 2 格当成 2 小时显示，标签直接翻倍。
     *
     * @param slots 格数
     * @return 小时
     */
    public static int hoursOfSlots(int slots) {
        return slots / SLOTS_PER_HOUR;
    }

    /**
     * 时段格数 → 分钟。
     *
     * @param slots 格数
     * @return 分钟
     */
    public static int minutesOfSlots(int slots) {
        return slots * SLOT_MINUTES;
    }

    /**
     * 时间 → 时段序号（向下取整到所在格）。
     *
     * @param time 时间
     * @return 时段序号
     */
    public static int slotOf(LocalTime time) {
        return (time.getHour() * 60 + time.getMinute()) / SLOT_MINUTES;
    }

    /**
     * 时段序号 → 时间。
     *
     * @param slot 时段序号
     * @return 该格起始时间
     */
    public static LocalTime timeOfSlot(int slot) {
        int minutes = slot * SLOT_MINUTES;
        // 跨小时进位交给 LocalTime.of 校验，超过 24:00 的边界由调用方保证
        return LocalTime.of((minutes / 60) % 24, minutes % 60);
    }

    /**
     * 时段序号格式化为 {@code HH:mm}。
     *
     * @param slot 时段序号
     * @return {@code 08:00} / {@code 08:30} 形式
     */
    public static String slotText(int slot) {
        LocalTime time = timeOfSlot(slot);
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /**
     * 把小时序号格式化为 {@code HH:00}。
     *
     * @param hour 小时（0~23）
     * @return {@code 08:00} 形式的字符串
     * @deprecated V2 起时段是 30 分钟格，请改用 {@link #slotText(int)}；本方法只在整小时语境下正确
     */
    @Deprecated
    public static String hourText(int hour) {
        return String.format("%02d:00", hour);
    }

    /**
     * 时段标签，如 {@code 14:00-14:30}。
     *
     * @param slotIndex 时段序号
     * @return 时段标签
     */
    public static String slotLabel(int slotIndex) {
        return slotText(slotIndex) + "-" + slotText(slotIndex + 1);
    }

    /**
     * 预约区间文本，如 {@code 14:00-16:00}。
     *
     * @param startSlot 起始时段
     * @param duration  时长（小时，内部换算为格数）
     * @return 区间文本
     */
    public static String rangeLabel(int startSlot, int duration) {
        return slotText(startSlot) + "-" + slotText(startSlot + slotsOf(duration));
    }

    /**
     * 由日期 + 时段序号构造起始时间点。
     *
     * @param date      日期
     * @param slotIndex 时段序号
     * @return 该时段起始时间
     */
    public static LocalDateTime slotStart(LocalDate date, int slotIndex) {
        return LocalDateTime.of(date, timeOfSlot(slotIndex));
    }

    /**
     * 由日期 + 时段序号构造结束时间点（开区间）。
     *
     * @param date      日期
     * @param slotIndex 结束时段的开区间边界序号
     * @return 该时段结束时间
     */
    public static LocalDateTime slotEnd(LocalDate date, int slotIndex) {
        return LocalDateTime.of(date, timeOfSlot(slotIndex));
    }

    /**
     * 判断时段序号是否可作为起始格。
     *
     * @param slotIndex 时段序号
     * @return 是否合法
     */
    public static boolean validSlotIndex(int slotIndex) {
        return slotIndex >= SLOT_INDEX_MIN && slotIndex <= SLOT_INDEX_MAX;
    }

    /**
     * 判断时长是否合法（1~4 小时）。
     *
     * @param duration 时长
     * @return 是否合法
     */
    public static boolean validDuration(Integer duration) {
        return duration != null && ALLOWED_DURATIONS.contains(duration);
    }

    /**
     * 把任意时段序号收敛到合法区间内（用于「当前时段」计算）。
     *
     * @param slot 时段序号
     * @return 合法时段序号
     */
    public static int clampToSlotRange(int slot) {
        if (slot < SLOT_INDEX_MIN) {
            return SLOT_INDEX_MIN;
        }
        if (slot > SLOT_INDEX_MAX) {
            return SLOT_INDEX_MAX;
        }
        return slot;
    }

    /**
     * 手机号脱敏：{@code 138****1234}。
     *
     * @param phone 原始手机号，可为空
     * @return 脱敏后的手机号；空值返回空字符串
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        int len = phone.length();
        if (len <= PHONE_MASK_PREFIX + PHONE_MASK_SUFFIX) {
            return phone;
        }
        String prefix = phone.substring(0, PHONE_MASK_PREFIX);
        String suffix = phone.substring(len - PHONE_MASK_SUFFIX);
        return prefix + "****" + suffix;
    }

    /**
     * 格式化日期时间为 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param time 时间，可为空
     * @return 格式化字符串；空值返回 null
     */
    public static String formatDateTime(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME_FORMATTER);
    }

    /**
     * 格式化日期为 {@code yyyy-MM-dd}。
     *
     * @param date 日期，可为空
     * @return 格式化字符串；空值返回 null
     */
    public static String formatDate(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMATTER);
    }

    /**
     * 保留一位小数的比率（0~100），分母为 0 时返回 0。
     *
     * @param numerator   分子
     * @param denominator 分母
     * @return 百分比，保留一位小数
     */
    public static double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}

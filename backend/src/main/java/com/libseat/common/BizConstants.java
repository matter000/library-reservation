package com.libseat.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 全局业务常量与时段工具方法（DESIGN §8.4）。
 *
 * <p>开放时间 08:00–22:00，粒度 1 小时，共 14 个时段，slot_index = 8~21。
 */
public final class BizConstants {

    /** 开放结束小时（开区间）。 */
    public static final int OPEN_HOUR_END = 22;

    /** 时段总数。 */
    public static final int SLOT_COUNT = 14;

    /** slot_index 最小值。 */
    public static final int SLOT_INDEX_MIN = 8;

    /** slot_index 最大值（21:00-22:00）。 */
    public static final int SLOT_INDEX_MAX = 21;

    /** 默认时长。 */
    public static final int DEFAULT_DURATION = 2;

    /** 可选时长；上限即本列表最大值 4 小时。 */
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
     * 把小时序号格式化为 {@code HH:mm}。
     *
     * @param hour 小时（0~23）
     * @return {@code 08:00} 形式的字符串
     */
    public static String hourText(int hour) {
        return String.format("%02d:00", hour);
    }

    /**
     * 时段标签，如 {@code 14:00-15:00}。
     *
     * @param slotIndex 时段序号
     * @return 时段标签
     */
    public static String slotLabel(int slotIndex) {
        return hourText(slotIndex) + "-" + hourText(slotIndex + 1);
    }

    /**
     * 预约时段区间文本，如 {@code 14:00-16:00}。
     *
     * @param startSlot 起始时段
     * @param duration  时长（小时）
     * @return 区间文本
     */
    public static String rangeLabel(int startSlot, int duration) {
        return hourText(startSlot) + "-" + hourText(startSlot + duration);
    }

    /**
     * 由日期 + 时段序号构造时间点。
     *
     * @param date      日期
     * @param slotIndex 时段序号
     * @return 该时段起始时间
     */
    public static LocalDateTime slotStart(LocalDate date, int slotIndex) {
        return LocalDateTime.of(date, LocalTime.of(slotIndex, 0));
    }

    /**
     * 由日期 + 时段序号构造结束时间点（开区间）。
     *
     * @param date      日期
     * @param slotIndex 结束时段序号
     * @return 该时段结束时间
     */
    public static LocalDateTime slotEnd(LocalDate date, int slotIndex) {
        return LocalDateTime.of(date, LocalTime.of(slotIndex, 0));
    }

    /**
     * 判断时段序号是否合法（8~21）。
     *
     * @param slotIndex 时段序号
     * @return 是否合法
     */
    public static boolean validSlotIndex(int slotIndex) {
        return slotIndex >= SLOT_INDEX_MIN && slotIndex <= SLOT_INDEX_MAX;
    }

    /**
     * 判断时长是否合法（1~4）。
     *
     * @param duration 时长
     * @return 是否合法
     */
    public static boolean validDuration(Integer duration) {
        return duration != null && ALLOWED_DURATIONS.contains(duration);
    }

    /**
     * 把任意小时数收敛到合法时段区间内（用于「当前时段」计算）。
     *
     * @param hour 当前小时
     * @return 合法时段序号
     */
    public static int clampToSlotRange(int hour) {
        if (hour < SLOT_INDEX_MIN) {
            return SLOT_INDEX_MIN;
        }
        if (hour > SLOT_INDEX_MAX) {
            return SLOT_INDEX_MAX;
        }
        return hour;
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

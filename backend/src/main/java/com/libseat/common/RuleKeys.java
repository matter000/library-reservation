package com.libseat.common;

/**
 * 规则键常量（★ 键名的唯一来源）。
 *
 * <p>配置表里的键、后端读取处、管理端展示必须用同一份常量，
 * 避免字符串字面量散落各处导致「改了配置但没人读」的静默失效。
 *
 * <p>只登记**已被消费**的键；未接线的配置属于死配置，随各分片逐个加入。
 */
public final class RuleKeys {

    /** 签到窗口：开始前多少分钟。 */
    public static final String BOOK_CHECK_IN_BEFORE_MIN = "book.check-in-before-min";

    /** 签到窗口：开始后多少分钟（超时未签到即 NO_SHOW）。 */
    public static final String BOOK_CHECK_IN_AFTER_MIN = "book.check-in-after-min";

    /** 可提前预约天数（含当天）。 */
    public static final String BOOK_ADVANCE_DAYS = "book.advance-days";

    /** 预约默认时长（小时）。 */
    public static final String BOOK_DEFAULT_DURATION = "book.default-duration";

    /** 可选时长列表（小时，逗号分隔）。 */
    public static final String BOOK_ALLOWED_DURATIONS = "book.allowed-durations";

    /** 信用闸门总开关：false 时只记录不限制（演示逃生阀）。 */
    public static final String CREDIT_ENFORCE_ENABLED = "credit.enforce_enabled";

    /** 初始信用分。 */
    public static final String CREDIT_INIT_SCORE = "credit.init-score";

    /** 低于此分 → 高峰时段禁约。 */
    public static final String CREDIT_PEAK_BAN_UNDER = "credit.peak-ban-under";

    /** 低于此分 → 暂停预约 3 天。 */
    public static final String CREDIT_BAN_3D_UNDER = "credit.ban-3d-under";

    /** 低于此分 → 暂停预约 7 天。 */
    public static final String CREDIT_BAN_7D_UNDER = "credit.ban-7d-under";

    /** 低于此分（即 0 分）→ 暂停至学期末。 */
    public static final String CREDIT_SEMESTER_UNDER = "credit.semester-under";

    /** 暂停预约 3 天的天数。 */
    public static final String CREDIT_BAN_3D_DAYS = "credit.ban-3d-days";

    /** 暂停预约 7 天的天数。 */
    public static final String CREDIT_BAN_7D_DAYS = "credit.ban-7d-days";

    /** 连续主动结束满 N 次的恢复加分。 */
    public static final String CREDIT_STREAK_BONUS = "credit.streak-bonus";

    /** 多少次连续主动结束算一档。 */
    public static final String CREDIT_STREAK_SIZE = "credit.streak-size";

    private RuleKeys() {
        // 常量类禁止实例化
    }
}

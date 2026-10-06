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

    /** 单日累计预约上限（分钟，全馆口径）。 */
    public static final String BOOK_DAILY_CAP_MIN = "book.daily-cap-min";

    /** 考试周单日累计预约上限（分钟，PRD §12-3「考试周 8 小时」）。 */
    public static final String BOOK_DAILY_CAP_EXAM_MIN = "book.daily-cap-exam-min";

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

    /** 动态高峰：分区占用率达到该百分比开始计时。 */
    public static final String PEAK_DYNAMIC_THRESHOLD = "peak.dynamic-threshold";

    /** 动态高峰：连续超阈多少分钟后才认定。 */
    public static final String PEAK_DYNAMIC_SUSTAIN_MIN = "peak.dynamic-sustain-min";

    /** 动态高峰：认定后至少保持多少分钟（回落不立即解除）。 */
    public static final String PEAK_DYNAMIC_HOLD_MIN = "peak.dynamic-hold-min";

    /** 高峰时段单次时长上限（分钟）。 */
    public static final String PEAK_COMPRESS_MIN = "peak.compress-min";

    /** 高峰时段改为前一天这个时刻才开放（{@code HH:mm}）。 */
    public static final String PEAK_OPEN_TIME = "peak.open-time";

    /** 教师单次时长上限（分钟），用于覆盖分区上限。 */
    public static final String BOOK_MAX_DURATION_TEACHER_MIN = "book.max-duration-teacher-min";

    /** 候补 offer 的确认窗口（分钟）。 */
    public static final String WAITLIST_OFFER_MIN = "waitlist.offer-min";

    /** 同一用户最多能挂几条活动候补。 */
    public static final String WAITLIST_MAX_ACTIVE = "waitlist.max-active";

    /** 暂离到期前多少分钟发站内提醒。 */
    public static final String NOTICE_LEAVE_REMIND_MIN = "notice.leave-remind-min";

    /** 签到后连续使用无暂离多少分钟即疑似占座。 */
    public static final String PATROL_SUSPECT_MIN = "patrol.suspect-min";

    /** 疑似占座的另一条件：预约剩余不少于多少分钟（快结束的不打扰）。 */
    public static final String PATROL_SUSPECT_MIN_LEFT = "patrol.suspect-min-left";

    /** 组队签到后多少分钟仍未确认入座。 */
    public static final String PATROL_GROUP_ABSENT_MIN = "patrol.group-absent-min";

    /** 同座位同类型被提醒后多少分钟内再犯即升为 HIGH。 */
    public static final String PATROL_ESCALATE_MIN = "patrol.escalate-min";

    private RuleKeys() {
        // 常量类禁止实例化
    }
}

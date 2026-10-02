package com.libseat.enums;

import lombok.Getter;

/**
 * 信用流水类型（账本只追加，余额可追溯）。
 *
 * <p>★ 不变式：任意时刻 {@code sum(t_credit_log.delta) == t_user.credit_score}，
 * 因此「初始分」也必须是一笔流水（{@link #INIT}），否则对账接口永远对不平。
 * 撤销申诉走 {@link #RESTORE} 反向补分，不允许改写或删掉历史行。
 */
@Getter
public enum CreditLogKind {

    /** 初始分（注册或迁移折算）。 */
    INIT("初始分"),

    /** 违规扣分。 */
    PENALTY("违规扣分"),

    /** 连续主动结束加分。 */
    STREAK_BONUS("良好使用奖励"),

    /** 上一自然周无违规的恢复加分（DESIGN §6.4）。 */
    WEEKLY_BONUS("周恢复奖励"),

    /** 申诉成立后的回补。 */
    RESTORE("申诉回补");

    private final String text;

    CreditLogKind(String text) {
        this.text = text;
    }
}

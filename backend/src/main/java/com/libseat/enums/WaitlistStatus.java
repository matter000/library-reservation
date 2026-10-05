package com.libseat.enums;

import lombok.Getter;

/**
 * 候补状态（DESIGN §8.3）。
 *
 * <pre>
 * WAITING ──有座位释放──→ OFFERED ──本人确认──→ CONFIRMED
 *    ↑                      │
 *    └──────顺延下一位──────┴──超时──→ EXPIRED
 * 本人主动退出 → CANCELLED
 * </pre>
 *
 * <p>★ 超时置 {@link #EXPIRED} 时<b>不记违规</b>：候补是「没抢到位置」而不是「放了鸽子」，
 * 给没占到座位的人记爽约账，等于惩罚使用候补功能。
 */
@Getter
public enum WaitlistStatus {

    /** 排队中，等释放。 */
    WAITING("排队中"),

    /** 已收到 offer，等待本人在窗口内确认。 */
    OFFERED("待确认"),

    /** 已确认并成功下单。 */
    CONFIRMED("已确认"),

    /** 窗口内未确认，已顺延下一位。 */
    EXPIRED("已过期"),

    /** 本人退出队列。 */
    CANCELLED("已取消");

    private final String text;

    WaitlistStatus(String text) {
        this.text = text;
    }

    /**
     * 是否仍占着「活动名额」（决定 {@code active_flag} 与唯一索引）。
     *
     * @return WAITING / OFFERED 为真
     */
    public boolean active() {
        return this == WAITING || this == OFFERED;
    }
}

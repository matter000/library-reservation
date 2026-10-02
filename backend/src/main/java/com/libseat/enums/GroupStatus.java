package com.libseat.enums;

import lombok.Getter;

/**
 * 队伍状态（DESIGN §7.1）。
 *
 * <pre>
 * FORMING ──到 lock_time 且人数达标──→ LOCKED ──整桌下单成功──→ RESERVED ──组长签到──→ CHECKED_IN ──→ COMPLETED
 *    └──人数不足 / 组长取消 / 任一座位被抢──→ FAILED / CANCELLED
 * </pre>
 *
 * <p>★ {@link #LOCKED} 与 {@link #RESERVED} 分开是有意的：锁定是「成员冻结、开始占座」这个动作，
 * 下单成功才是「桌上每个座位都有预约了」。合成一个状态就没法解释
 * 「锁定成功但有一格被抢、整单回滚」这一刻队伍到底处在什么位置。
 */
@Getter
public enum GroupStatus {

    /** 组队中，可邀请、可确认、可退出。 */
    FORMING("组队中"),

    /** 已锁定，成员冻结，正在按桌下单。 */
    LOCKED("已锁定"),

    /** 已下单，桌上座位都有预约。 */
    RESERVED("已订座"),

    /** 组长已签到（= 全队签到）。 */
    CHECKED_IN("使用中"),

    /** 已完成。 */
    COMPLETED("已完成"),

    /** 组长或系统取消。 */
    CANCELLED("已取消"),

    /** 人数不足等原因未能成团。 */
    FAILED("未成团");

    private final String text;

    GroupStatus(String text) {
        this.text = text;
    }

    /**
     * 是否还能增减成员（DESIGN §7.2：锁定之后就冻结）。
     *
     * @return 是否可增减
     */
    public boolean memberMutable() {
        return this == FORMING;
    }

    /**
     * 是否已是终态（不再有任何流转）。
     *
     * @return 是否终态
     */
    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}

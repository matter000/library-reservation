package com.libseat.enums;

import lombok.Getter;

/**
 * 成员入座状态（DESIGN §7.2，T-V2-16 消费）。
 *
 * <p>★ 组长签到只把全队预约推到 {@code CHECKED_IN}，成员本人是否到场是另一件事，
 * 所以这里初值一律 {@link #UNKNOWN}，不能由组长签到顺带写成 PRESENT。
 */
@Getter
public enum AttendStatus {

    /** 尚未确认入座。 */
    UNKNOWN("待确认入座"),

    /** 已确认入座（扫码）。 */
    PRESENT("已入座"),

    /** 超时未入座（记违规，但座位保留给队伍）。 */
    ABSENT("未入座");

    private final String text;

    AttendStatus(String text) {
        this.text = text;
    }
}

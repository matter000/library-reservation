package com.libseat.enums;

import lombok.Getter;

/**
 * 座位展示态（派生，不落库）。
 *
 * <p>由 {@code OccupancyService} 按「日期 + 时段」实时派生，DESIGN §6.1。
 */
@Getter
public enum SeatDisplayStatus {

    /** 空闲（绿）。 */
    FREE("FREE", "空闲"),

    /** 已预约（橙黄）。 */
    RESERVED("RESERVED", "已预约"),

    /** 使用中（红）。 */
    IN_USE("IN_USE", "使用中"),

    /** 不可用（灰）。 */
    UNAVAILABLE("UNAVAILABLE", "不可用");

    private final String code;

    private final String text;

    SeatDisplayStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

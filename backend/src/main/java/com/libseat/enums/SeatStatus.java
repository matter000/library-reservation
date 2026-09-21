package com.libseat.enums;

import lombok.Getter;

/**
 * 座位基础状态（持久化到 t_seat.status，与占用无关）。
 */
@Getter
public enum SeatStatus {

    /** 可用。 */
    AVAILABLE("AVAILABLE", "可用"),

    /** 维护中。 */
    MAINTENANCE("MAINTENANCE", "维护中"),

    /** 已停用。 */
    DISABLED("DISABLED", "已停用");

    private final String code;

    private final String text;

    SeatStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

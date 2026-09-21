package com.libseat.enums;

import lombok.Getter;

/**
 * 楼层状态。
 */
@Getter
public enum FloorStatus {

    /** 开放中。 */
    OPEN("OPEN", "开放中"),

    /** 已关闭（平面图全部座位 UNAVAILABLE）。 */
    CLOSED("CLOSED", "已关闭");

    private final String code;

    private final String text;

    FloorStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

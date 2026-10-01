package com.libseat.enums;

import lombok.Getter;

/**
 * 分区讨论音量等级（持久化到 t_zone.allow_discuss）。
 */
@Getter
public enum DiscussLevel {

    /** 禁止交谈。 */
    NO("NO", "禁止交谈"),

    /** 低声交流。 */
    LOW("LOW", "低声交流"),

    /** 允许讨论。 */
    YES("YES", "允许讨论");

    private final String code;

    private final String text;

    DiscussLevel(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

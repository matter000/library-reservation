package com.libseat.enums;

import lombok.Getter;

/**
 * 分区类型（持久化到 t_zone.zone_type）。
 *
 * <p>类型只用于归类与前端展示；时长上限、暂离、组队等规则一律读分区行本身，
 * 不在此处硬编码——同一类型在不同楼层允许配出不同上限。
 */
@Getter
public enum ZoneType {

    /** 静音自习。 */
    SILENT("SILENT", "静音自习"),

    /** 研讨协作（默认仅接受整组预约）。 */
    SEMINAR("SEMINAR", "研讨协作"),

    /** 电子阅览。 */
    E_READING("E_READING", "电子阅览"),

    /** 休闲阅览。 */
    LOUNGE("LOUNGE", "休闲阅览"),

    /** 考研专座。 */
    EXAM_PREP("EXAM_PREP", "考研专座"),

    /** 教师专区。 */
    TEACHER_ONLY("TEACHER_ONLY", "教师专区");

    private final String code;

    private final String text;

    ZoneType(String code, String text) {
        this.code = code;
        this.text = text;
    }
}

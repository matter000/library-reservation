package com.libseat.enums;

import lombok.Getter;

/**
 * 暂离状态（DESIGN §5.1）。
 *
 * <p>{@code OPEN} 期间 {@code open_flag=1}，其余状态必须为 {@code NULL} ——
 * 这个配对由唯一索引 {@code uk_leave_open (reservation_id, open_flag)} 强制，
 * 不是应用层约定。
 */
@Getter
public enum LeaveStatus {

    /** 暂离中，尚未返回。 */
    OPEN("OPEN", "暂离中"),

    /** 已按时返回。 */
    RETURNED("RETURNED", "已返回"),

    /** 超时未返回（由 §5.3 的扫描任务判定）。 */
    TIMEOUT("TIMEOUT", "超时未归");

    /** OPEN 态写入 open_flag 的固定值。 */
    public static final int OPEN_FLAG = 1;

    private final String code;

    private final String text;

    LeaveStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * 该状态是否应占用「一条预约至多一条 OPEN」的名额。
     *
     * @return 是否活跃
     */
    public boolean isActive() {
        return this == OPEN;
    }
}

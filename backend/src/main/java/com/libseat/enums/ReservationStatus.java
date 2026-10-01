package com.libseat.enums;

import lombok.Getter;

/**
 * 预约状态。
 *
 * <p>只有 {@code CONFIRMED} 与 {@code CHECKED_IN} 占用座位；
 * {@code COMPLETED / CANCELLED / NO_SHOW / VIOLATED / ADMIN_CANCELLED} 均视为已释放（slot 行已物理删除）。
 */
@Getter
public enum ReservationStatus {

    /** 已预约（待开始）。 */
    CONFIRMED("CONFIRMED", "已预约"),

    /** 使用中（已签到）。 */
    CHECKED_IN("CHECKED_IN", "使用中"),

    /** 已完成。 */
    COMPLETED("COMPLETED", "已完成"),

    /** 已取消（用户主动）。 */
    CANCELLED("CANCELLED", "已取消"),

    /** 超时未签到。 */
    NO_SHOW("NO_SHOW", "未签到"),

    /** 暂离超时未归（V2 §5.3；与 NO_SHOW 一样已释放座位，但成因与处罚不同）。 */
    VIOLATED("VIOLATED", "暂离未归"),

    /** 已被管理员取消（强制释放）。 */
    ADMIN_CANCELLED("ADMIN_CANCELLED", "已被管理员取消");

    private final String code;

    private final String text;

    ReservationStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * 是否占用座位（会写入 t_reservation_slot）。
     *
     * @return 是否占用
     */
    public boolean isOccupying() {
        return this == CONFIRMED || this == CHECKED_IN;
    }
}

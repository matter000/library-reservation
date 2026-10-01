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

    /**
     * 暂离中（蓝色系，S2 / T-V2-10 才产出该态）。
     *
     * <p>此处预留仅为把「座位仍属该预约，但人不在」与 {@link #IN_USE} 区分开：
     * 派生入口、前端图例与占用统计口径在暂离片一并接通，本片不会产生该值。
     */
    ON_LEAVE("ON_LEAVE", "暂离中"),

    /** 不可用（灰）。 */
    UNAVAILABLE("UNAVAILABLE", "不可用");

    private final String code;

    private final String text;

    SeatDisplayStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * 该展示态是否仍算「占用中」（座位不可被他人预约）。
     *
     * <p>★ 暂离也算占用：这是「四处余量必须同源」最容易写错的一处 ——
     * 任何按维度聚合占用的地方都要用本方法判定，不要再手写一次状态白名单。
     *
     * @return 是否占用
     */
    public boolean occupying() {
        return this == RESERVED || this == IN_USE || this == ON_LEAVE;
    }

    /**
     * 该展示态是否计入可用座位（分母）。
     *
     * @return 是否可用
     */
    public boolean usable() {
        return this != UNAVAILABLE;
    }
}

package com.libseat.enums;

import lombok.Getter;

/**
 * 处罚状态。
 *
 * <p>{@link #RELEASED} 有两个来源：到期（{@code end_time} 已过，由查询侧判定即可，
 * 不要求必须有任务去翻状态）与人工解除（写 {@code released_by} + {@code release_note}）。
 * 闸门判活一律用「ACTIVE 且未过期」，避免依赖某个定时任务有没有跑过。
 */
@Getter
public enum PenaltyStatus {

    /** 生效中。 */
    ACTIVE("生效中"),

    /** 已解除（到期或人工）。 */
    RELEASED("已解除");

    private final String text;

    PenaltyStatus(String text) {
        this.text = text;
    }
}

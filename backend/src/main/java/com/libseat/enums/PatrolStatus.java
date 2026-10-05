package com.libseat.enums;

import lombok.Getter;

/**
 * 巡查任务状态（DESIGN §11）。
 *
 * <p>★ 只有 {@link #PENDING} 占「未处理」名额（配合 {@code open_flag} 与唯一索引）：
 * 四种处置都是终态。馆员口头提醒后如果人还坐着，下一轮生成器会<b>另开一条</b>任务，
 * 并按 {@code patrol.escalate-min} 把等级升到 HIGH —— 这样「提醒过几次」在账上看得见，
 * 而不是把一条任务无限期挂着。
 */
@Getter
public enum PatrolStatus {

    /** 待处理。 */
    PENDING("待处理"),

    /** 已口头提醒。 */
    REMIND("已提醒"),

    /** 已强制释放（走馆员释放入口，必填原因）。 */
    RELEASED("已释放"),

    /** 已裁定违规（证据随本次处置固化）。 */
    VIOLATION("已裁定违规"),

    /** 已忽略（误报或已自行离开）。 */
    IGNORED("已忽略");

    /** 状态文案。 */
    private final String text;

    PatrolStatus(String text) {
        this.text = text;
    }

    /**
     * 是否仍占着「该座位的未处理名额」。
     *
     * @return 仅 PENDING 为真
     */
    public boolean open() {
        return this == PENDING;
    }
}

package com.libseat.enums;

import lombok.Getter;

/**
 * 巡查任务类型（DESIGN §11，T-V2-21）。
 *
 * <p>前三类由生成器按规则登记，第四类由馆员现场登记。
 * ★ 类型只回答「疑点是什么」，不回答「该怎么处置」—— 处置动作是 {@link PatrolAction}，
 * 两者混在一起就会出现「机器判了 RELEASE」这种越权状态。
 */
@Getter
public enum PatrolType {

    /** 签到后长时间连续使用且无暂离。 */
    SUSPECTED_OCCUPY("疑似占座"),

    /** 队伍已签到但成员迟迟未确认入座。 */
    GROUP_NO_ATTEND("组队未入座"),

    /** 预约已结束仍占用座位。 */
    OVERDUE_HOLDING("到期仍占"),

    /** 馆员现场登记（含无预约占座）。 */
    MANUAL("人工登记");

    /** 类型文案。 */
    private final String text;

    PatrolType(String text) {
        this.text = text;
    }
}

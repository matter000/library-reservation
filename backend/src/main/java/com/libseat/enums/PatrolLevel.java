package com.libseat.enums;

import lombok.Getter;

/**
 * 巡查等级（DESIGN §11）：馆员看板的排序与筛选依据。
 *
 * <p>等级由生成时的事实算出（连续时长、是否提醒过、是否已到期），不是人工填的字段 ——
 * 否则同一件事在不同馆员手里会有不同优先级，看板就失去了可比性。
 */
@Getter
public enum PatrolLevel {

    /** 立即处理。 */
    HIGH("高"),

    /** 顺路处理。 */
    MID("中"),

    /** 记录备查。 */
    LOW("低");

    /** 文案。 */
    private final String text;

    PatrolLevel(String text) {
        this.text = text;
    }
}

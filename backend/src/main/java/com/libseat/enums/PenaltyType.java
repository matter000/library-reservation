package com.libseat.enums;

import lombok.Getter;

/**
 * 处罚类型（信用分阶梯，DESIGN §6.2）。
 *
 * <p>阶梯只由 {@code CreditService} 在分数变动时评估生成，人工不直接挂处罚；
 * 解除入口另计（馆员 / 系统管理员）。
 */
@Getter
public enum PenaltyType {

    /** 高峰时段禁约：随分数回升在加分路径上自动解除，无固定结束时刻。 */
    PEAK_BAN("高峰时段禁约", false),

    /** 暂停预约 3 天。 */
    BOOK_BAN_3D("暂停预约 3 天", true),

    /** 暂停预约 7 天（并需到馆签承诺书）。 */
    BOOK_BAN_7D("暂停预约 7 天", true),

    /** 暂停至学期末：只能由系统管理员解除。 */
    SEMESTER_BAN("暂停至学期末", false);

    private final String text;

    /** 是否按天数自动到期（false 表示需要加分或人工解除）。 */
    private final boolean timed;

    PenaltyType(String text, boolean timed) {
        this.text = text;
        this.timed = timed;
    }
}

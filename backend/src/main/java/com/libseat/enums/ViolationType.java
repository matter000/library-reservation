package com.libseat.enums;

import lombok.Getter;

/**
 * 违规类型与默认扣分（PRD §6.3 扣分表）。
 *
 * <p>★ DESIGN §6.1 的注释里还列了 {@code NO_CANCEL}，但 PRD 的扣分表只有一项
 * 「预约后未到且未取消 −10」，那正是 {@link #NO_SHOW} 的语义，同义再开一个类型
 * 会让同一件事有两条统计口径，故不落地。
 *
 * <p>扣分幅度是产品口径，写在枚举里由 {@code CreditService} 单点取用；
 * 调用方需要覆盖时（如馆员现场裁量）显式传 delta。
 */
@Getter
public enum ViolationType {

    /** 爽约：预约后未到且未取消。 */
    NO_SHOW("爽约", -10),

    /** 暂离超时未归（§5.3 扫描判定）。 */
    LEAVE_TIMEOUT("暂离未归", -10),

    /** 组队成员锁定后未入座（座位仍留给队伍，不释放）。 */
    GROUP_ABSENT("组队缺席", -10),

    /** 组长未签到：全队取消，责任在组长。 */
    LEADER_ABSENT("组长缺席", -20),

    /** 现场占座（巡查裁定）。 */
    ON_SITE_OCCUPY("现场占座", -15);

    private final String text;

    private final int defaultDelta;

    ViolationType(String text, int defaultDelta) {
        this.text = text;
        this.defaultDelta = defaultDelta;
    }
}

package com.libseat.enums;

import lombok.Getter;

/**
 * 成员邀请状态（DESIGN §7.1）。
 *
 * <p>★ {@link #EXPIRED} 是「惰性判定」而不是定时任务：邀请有效期（10 分钟）在读成员表 /
 * 确认 / 锁定这三处都会检查一次，因为队伍是短时对象 —— 专门加一个每分钟扫全表的代价，
 * 换不来任何用户能感知的及时性。
 */
@Getter
public enum InviteStatus {

    /** 已邀请，等待确认。 */
    INVITED("待确认"),

    /** 已确认入座，计入成团人数。 */
    CONFIRMED("已确认"),

    /** 本人婉拒。 */
    DECLINED("已婉拒"),

    /** 超时未确认。 */
    EXPIRED("已过期");

    private final String text;

    InviteStatus(String text) {
        this.text = text;
    }

    /**
     * 是否算作「已到齐的人数」。
     *
     * @return 是否已确认
     */
    public boolean counted() {
        return this == CONFIRMED;
    }
}

package com.libseat.enums;

import lombok.Getter;

/**
 * 申诉状态（DESIGN §6.3 状态机）。
 *
 * <pre>
 * PENDING ──馆员复核──→ APPROVED（终态，违规撤销 + 分数回补）
 *    │                  └→ REJECTED ──学生升级──→ ESCALATED ──SYS_ADMIN 终审──→ FINAL_JUDGED（终态）
 * </pre>
 *
 * <p>★ {@link #FINAL_JUDGED} 是终态：终审之后既不能再裁定，也不能再提交申诉，
 * 否则一条违规会永远停在「有人还能翻案」的状态里，用户和馆员都判不出到底哪句是结论。
 */
@Getter
public enum AppealStatus {

    /** 待馆员复核。 */
    PENDING("待复核"),

    /** 复核成立：违规撤销、分数已反向回补。 */
    APPROVED("申诉成立"),

    /** 复核驳回（附理由），学生可升级终审。 */
    REJECTED("申诉驳回"),

    /** 学生已升级，等待系统管理员终审。 */
    ESCALATED("已升级终审"),

    /** 已终审（无论成立或驳回），终态。 */
    FINAL_JUDGED("已终审");

    private final String text;

    AppealStatus(String text) {
        this.text = text;
    }

    /**
     * 是否还能被裁定（只有这两态进得了裁定入口）。
     *
     * @return 是否可裁定
     */
    public boolean judgeable() {
        return this == PENDING || this == ESCALATED;
    }

    /**
     * 是否还能由学生侧动作改变（驳回后可升级；成立或终审后就定案了）。
     *
     * @return 是否可升级
     */
    public boolean escalatable() {
        return this == REJECTED;
    }
}

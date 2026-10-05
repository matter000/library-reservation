package com.libseat.enums;

import lombok.Getter;

/**
 * 馆员处置动作（DESIGN §11）。
 *
 * <p>★ 与 {@link PatrolStatus} 分开是有意的：动作是「馆员此刻做了什么」，状态是「这条任务落在哪个终态」，
 * 两者名字并不同形（RELEASE → RELEASED、IGNORE → IGNORED），合成一个枚举就只能
 * 让接口接受 {@code PENDING} 这种「处置出自处置」的输入。
 */
@Getter
public enum PatrolAction {

    /** 口头提醒，不动座位。 */
    REMIND("口头提醒", PatrolStatus.REMIND),

    /** 强制释放：复用 {@code AdminUsageService.release}，必填原因、通知学生、留审计。 */
    RELEASE("强制释放", PatrolStatus.RELEASED),

    /** 现场占座裁定违规：走 {@code CreditService.applyViolation(ON_SITE_OCCUPY)} 并固化本次证据链。 */
    VIOLATION("裁定违规", PatrolStatus.VIOLATION),

    /** 忽略：误报或已自行离开。 */
    IGNORE("忽略", PatrolStatus.IGNORED);

    /** 动作文案。 */
    private final String text;

    /** 处置后的任务状态。 */
    private final PatrolStatus targetStatus;

    PatrolAction(String text, PatrolStatus targetStatus) {
        this.text = text;
        this.targetStatus = targetStatus;
    }
}

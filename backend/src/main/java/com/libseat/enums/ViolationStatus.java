package com.libseat.enums;

import lombok.Getter;

/**
 * 违规记录状态。
 *
 * <p>{@link #REVOKED} 由申诉成立触发，必须同时反向补分（写 {@code RESTORE} 流水），
 * 不允许「撤销违规但分数不回」。
 */
@Getter
public enum ViolationStatus {

    /** 生效中。 */
    ACTIVE("生效中"),

    /** 申诉处理中（此时处罚不解除，等裁定结果）。 */
    APPEALING("申诉中"),

    /** 已撤销（申诉成立，分数已回补）。 */
    REVOKED("已撤销");

    private final String text;

    ViolationStatus(String text) {
        this.text = text;
    }
}

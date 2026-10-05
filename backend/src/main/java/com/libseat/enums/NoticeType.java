package com.libseat.enums;

import lombok.Getter;

/**
 * 站内通知类型（DESIGN §8.4，T-V2-20）。
 *
 * <p>★ 每种类型自带「点了去哪里」：本系统没有推送通道，通知是「轮到你处理」的入口，
 * 把跳转路径写进枚举而不是前端，是为了新增类型时不会出现「消息发得出去、链接靠猜」。
 * 路径必须与前端 router 和 {@code WebConfig} 的 SPA 白名单一致，三处漂一处就是死链。
 */
@Getter
public enum NoticeType {

    /** 签到窗口已开启。 */
    CHECK_IN_REMIND("可签到提醒", "/my/reservations"),

    /** 暂离已登记，含应归时刻。 */
    LEAVE_OPEN("暂离登记成功", "/my/reservations"),

    /** 暂离即将到期（同一暂离记录只发一次）。 */
    LEAVE_EXPIRE("暂离即将到期", "/my/reservations"),

    /** 违规即时通知（含申诉入口）。 */
    VIOLATION("违规裁定", "/credit"),

    /** 申诉裁定结果。 */
    APPEAL_RESULT("申诉已裁定", "/credit"),

    /** 候补 offer：轮到本人确认，窗口很短。 */
    WAITLIST_OFFER("候补轮到你了", "/my/waitlist"),

    /** 组长未在窗口内签到，整队被取消。 */
    GROUP_LEADER_ABSENT("队伍已取消", "/groups"),

    /**
     * 馆员巡查时强制释放了本人的座位（DESIGN §11 处置 RELEASE 要求通知学生）。
     *
     * <p>★ 这个类型不在 §8.4 的初稿清单里：写初稿时释放只有管理员入口，
     * T-V2-21 把「强制释放」开放给馆员处置后，「座位突然没了」就成了必须有解释的事件。
     */
    SEAT_RELEASED("座位已被释放", "/my/reservations");

    /** 类型文案。 */
    private final String text;

    /** 前端落地路径（深链）。 */
    private final String link;

    NoticeType(String text, String link) {
        this.text = text;
        this.link = link;
    }
}

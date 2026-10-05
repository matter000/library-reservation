package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 站内通知 DTO（T-V2-20）。
 *
 * <p>★ {@code link} 由服务端给出（枚举自带），前端只渲染：没有推送通道时，
 * 通知的价值全在「一点就到那件事」上，让前端按 type 猜路径，新增类型就会漏出死链。
 */
public class NoticeDTO {

    /**
     * 列表视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoticeListVO {

        /** 最近若干条（新的在前）。 */
        private List<NoticeVO> list;

        /** 未读条数（角标用，不必前端自己数）。 */
        private Long unread;

        /** 总条数：列表只回最近一屏，靠它说明「还有更早的」。 */
        private Long total;
    }

    /**
     * 单条通知。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoticeVO {

        /** 通知 ID。 */
        private Long noticeId;

        /** 类型枚举名。 */
        private String type;

        /** 类型文案。 */
        private String typeText;

        /** 标题。 */
        private String title;

        /** 正文。 */
        private String content;

        /** 前端落地路径。 */
        private String link;

        /** 指向的业务对象类型。 */
        private String refType;

        /** 指向的业务记录 ID。 */
        private Long refId;

        /** 是否已读。 */
        private Boolean readFlag;

        /** 行动窗口是否已过（只影响展示，不影响可读）。 */
        private Boolean expired;

        /** 窗口截止时刻。 */
        private String expireTime;

        /** 写入时刻。 */
        private String createTime;
    }
}

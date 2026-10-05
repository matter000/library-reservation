package com.libseat.entity;

import com.libseat.enums.NoticeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内通知的一条消息（t_notice）。
 *
 * <p>本系统没有第三方推送通道，「推送」在这套代码里就等于「写这张表 + 前端轮询」。
 * 因此通知是尽力而为的提醒，<b>不能作为任何判定的依据</b>：签到窗口、候补确认窗口、
 * 暂离应归时刻都由业务表与规则算，消息只是把人叫回页面上看一眼。
 *
 * <p>{@code (userId, type, refType, refId)} 上有唯一索引：提醒任务每分钟跑一次，
 * 同一件事只该打扰用户一次。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_notice")
public class Notice {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收件人。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 类型（自带文案与深链）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NoticeType type;

    /** 标题。 */
    @Column(name = "title", nullable = false, length = 50)
    private String title;

    /** 正文：要含可读的时刻与地点，用户不点开也能决策。 */
    @Column(name = "content", nullable = false, length = 300)
    private String content;

    /** 指向的业务对象类型（RESERVATION/LEAVE/WAITLIST/VIOLATION/APPEAL/GROUP）。 */
    @Column(name = "ref_type", length = 20)
    private String refType;

    /** 指向的业务记录 ID（去重键的一部分）。 */
    @Column(name = "ref_id")
    private Long refId;

    /** 是否已读。 */
    @Column(name = "read_flag", nullable = false)
    private Boolean readFlag;

    /** 有行动窗口的通知的截止时刻（如候补 offer）；无窗口为 null。 */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;

    /** 写入时刻。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    void onCreate() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (readFlag == null) {
            readFlag = Boolean.FALSE;
        }
    }
}

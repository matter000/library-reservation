package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.NoticeDTO;
import com.libseat.entity.Notice;
import com.libseat.enums.NoticeType;
import com.libseat.repository.NoticeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知的唯一写入口（DESIGN §8.4，T-V2-20）。
 *
 * <p>★ 本系统没有任何第三方推送通道：无 MQ、无 WebSocket、无短信。所谓「推送」在这套代码里
 * 就是「写 {@code t_notice} + 前端轮询」。因此通知是<b>尽力而为的提醒</b>，绝不能当判定依据 ——
 * 签到窗口、候补确认窗口、暂离应归时刻一律由业务表与规则算，消息只负责把人叫回页面上看一眼。
 * UI 也必须把这点讲清楚：候补 offer 只有十分钟窗口，人不守着页面就是会错过，这是设计后果不是故障。
 *
 * <p>★ 通知失败会连带业务事务一起回滚，这是有意的取舍：反过来（吞掉写入异常）会让
 * 「违规已经落库但用户永远收不到」和「消息发了两次」这两种情况都无法区分。
 * 真正需要防重复的是每分钟重跑的提醒任务，那条路靠 {@code uk_notice_ref} 唯一索引 +
 * 这里的存在性检查拦住，而不是靠捕获异常 —— 一次 flush 失败后 Hibernate 会话就不可用了，
 * 捕获只会把问题推迟到提交时刻变成 rollback-only 异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    /** 业务对象类型：预约。 */
    public static final String REF_RESERVATION = "RESERVATION";

    /** 业务对象类型：暂离记录。 */
    public static final String REF_LEAVE = "LEAVE";

    /** 业务对象类型：候补队列记录。 */
    public static final String REF_WAITLIST = "WAITLIST";

    /** 业务对象类型：违规。 */
    public static final String REF_VIOLATION = "VIOLATION";

    /** 业务对象类型：申诉。 */
    public static final String REF_APPEAL = "APPEAL";

    /** 业务对象类型：队伍。 */
    public static final String REF_GROUP = "GROUP";

    /** 铃铛一屏取多少条（更早的靠总数说明，不无限下发）。 */
    private static final int LIST_LIMIT = 50;

    private final NoticeRepository noticeRepository;

    /**
     * 写一条通知；同一（人, 类型, 业务对象）只写一次。
     *
     * @param userId     收件人
     * @param type       类型（自带文案与深链）
     * @param title      标题
     * @param content    正文，要含可读的时刻与地点
     * @param refType     业务对象类型，取本类的 {@code REF_*} 常量
     * @param refId      业务记录 ID（去重键的一部分）
     * @param expireTime 行动窗口截止时刻，无窗口传 null
     */
    @Transactional
    public void push(Long userId, NoticeType type, String title, String content,
                     String refType, Long refId, LocalDateTime expireTime) {
        if (noticeRepository.existsByUserIdAndTypeAndRefTypeAndRefId(userId, type, refType, refId)) {
            return;
        }
        noticeRepository.save(Notice.builder()
                .userId(userId)
                .type(type)
                .title(cut(title, 50))
                .content(cut(content, 300))
                .refType(refType)
                .refId(refId)
                .readFlag(Boolean.FALSE)
                .expireTime(expireTime)
                .createTime(LocalDateTime.now())
                .build());
        log.info("站内通知已写：userId={}, type={}, ref={}#{}", userId, type, refType, refId);
    }

    /**
     * 我的通知（新的在前）与未读数。
     *
     * @param userId 当前登录人
     * @return 列表视图
     */
    @Transactional(readOnly = true)
    public NoticeDTO.NoticeListVO list(Long userId) {
        List<NoticeDTO.NoticeVO> records = noticeRepository
                .findByUserIdOrderByIdDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(this::toVO)
                .toList();
        return NoticeDTO.NoticeListVO.builder()
                .list(records)
                .unread(noticeRepository.countByUserIdAndReadFlag(userId, Boolean.FALSE))
                .total(noticeRepository.countByUserId(userId))
                .build();
    }

    /**
     * 标记已读。
     *
     * <p>★ 按「ID + 收件人」取，别人的通知直接 4040：铃铛里的链接是靠 URL 点进来的，
     * 只按 ID 改就会让任何人标掉任何人的消息。
     *
     * @param userId 当前登录人
     * @param id     通知 ID
     * @return 更新后的通知
     */
    @Transactional
    public NoticeDTO.NoticeVO markRead(Long userId, Long id) {
        Notice notice = noticeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "通知不存在"));
        if (!Boolean.TRUE.equals(notice.getReadFlag())) {
            notice.setReadFlag(Boolean.TRUE);
            noticeRepository.save(notice);
        }
        return toVO(notice);
    }

    private NoticeDTO.NoticeVO toVO(Notice notice) {
        boolean expired = notice.getExpireTime() != null
                && !notice.getExpireTime().isAfter(LocalDateTime.now());
        return NoticeDTO.NoticeVO.builder()
                .noticeId(notice.getId())
                .type(notice.getType().name())
                .typeText(notice.getType().getText())
                .title(notice.getTitle())
                .content(notice.getContent())
                .link(notice.getType().getLink())
                .refType(notice.getRefType())
                .refId(notice.getRefId())
                .readFlag(notice.getReadFlag())
                // ★ 过期只影响展示（「已过期」灰掉），不影响可读：用户仍要能点开看看到发生了什么
                .expired(expired)
                .expireTime(BizConstants.formatDateTime(notice.getExpireTime()))
                .createTime(BizConstants.formatDateTime(notice.getCreateTime()))
                .build();
    }

    /** 数据库列有长度上限，超长直接截断而不是让一条文案把业务写坏。 */
    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

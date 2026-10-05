-- =====================================================================
-- V13：站内通知中心（S5 / T-V2-20）
--
-- 与 h2/V13__notice.sql 同一份结构，只改方言：IDENTITY → AUTO_INCREMENT、
-- TIMESTAMP → DATETIME、BOOLEAN 保留、editable 用 1/0。
-- 「为什么推送 = 站内通知 + 轮询」「为什么去重靠 uk_notice_ref」
-- 「为什么不做 PEAK_TIP」都写在 h2 版头部注释里，两边不得漂。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_notice (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '收件人',
    type        VARCHAR(30)  NOT NULL COMMENT '见 NoticeType',
    title       VARCHAR(50)  NOT NULL COMMENT '标题',
    content     VARCHAR(300) NOT NULL COMMENT '正文（含可读的时刻与地点，用户不必点开就能决策）',
    ref_type    VARCHAR(20)  NULL COMMENT 'RESERVATION/LEAVE/WAITLIST/VIOLATION/APPEAL/GROUP',
    ref_id      BIGINT       NULL COMMENT '指向的业务记录 ID（去重键的一部分）',
    read_flag   BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '已读状态',
    expire_time DATETIME     NULL COMMENT '有行动窗口的通知带截止（如候补 offer），UI 据此显示已过期',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时刻',
    UNIQUE KEY uk_notice_ref (user_id, type, ref_type, ref_id),
    KEY idx_notice_user (user_id, read_flag, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内通知';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'notice.leave-remind-min', '5', 'INT', 'BOOK', 1, '暂离到期前多少分钟发一次站内提醒（同一暂离记录只发一次）');

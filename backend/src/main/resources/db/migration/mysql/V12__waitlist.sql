-- =====================================================================
-- V12：候补队列（S5 / T-V2-19）
--
-- 与 h2/V12__waitlist.sql 同一份结构，只改方言：IDENTITY → AUTO_INCREMENT、
-- SMALLINT → TINYINT、TIMESTAMP → DATETIME、editable 用 1/0。
-- 「为什么排队键不含 seat_id」「为什么用 active_flag 的 NULL 唯一技巧」
-- 「为什么超时不记违规」都写在 h2 版头部注释里，两边不得漂。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_waitlist (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL COMMENT '候补人',
    floor_id          BIGINT       NOT NULL COMMENT '楼层（确认下单时要落到具体座位）',
    zone_id           BIGINT       NOT NULL COMMENT '排队键之一',
    table_id          BIGINT       NULL COMMENT '整桌候补用，普通区为 NULL',
    reserve_date      DATE         NOT NULL COMMENT '排队键之一',
    start_slot        INT          NOT NULL COMMENT '排队键之一（起始格）',
    end_slot          INT          NOT NULL COMMENT '开区间边界，确认时按此下单',
    status            VARCHAR(20)  NOT NULL COMMENT 'WAITING/OFFERED/CONFIRMED/EXPIRED/CANCELLED',
    active_flag       TINYINT      NULL COMMENT '活动中=1，终态=NULL（配合唯一索引）',
    offered_time      DATETIME     NULL COMMENT '置为 OFFERED 的时刻',
    offer_expire_time DATETIME     NULL COMMENT '确认窗口截止时刻',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_waitlist_active (user_id, zone_id, reserve_date, start_slot, active_flag),
    KEY idx_waitlist_zone_state (zone_id, reserve_date, start_slot, status),
    KEY idx_waitlist_user (user_id, active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='候补队列';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'waitlist.offer-min',  '10', 'INT', 'BOOK', 1, '候补 offer 的确认窗口（分钟），超时置 EXPIRED 并顺延下一位'),
    ('GLOBAL', '', 'waitlist.max-active', '2',  'INT', 'BOOK', 1, '同一用户最多能挂几条活动候补（分区各自排队）');

-- =====================================================================
-- V14：巡查任务（S6 / T-V2-21）
--
-- 与 h2/V14__patrol.sql 同一份结构，只改方言：IDENTITY → AUTO_INCREMENT、
-- SMALLINT → TINYINT、TIMESTAMP → DATETIME、CLOB → TEXT、editable 用 1/0。
-- 「为什么机器绝不自动释放」「为什么用 open_flag 的 NULL 唯一技巧」
-- 「为什么 evidence 无证据不落账」都写在 h2 版头部注释里，两边不得漂。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_patrol_task (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    floor_id       BIGINT       NOT NULL COMMENT '楼层',
    zone_id        BIGINT       NOT NULL COMMENT '分区',
    seat_id        BIGINT       NOT NULL COMMENT '疑点所在座位（同一座位同时只挂一个未处理任务）',
    reservation_id BIGINT       NULL COMMENT '关联预约；无预约占用为 NULL',
    type           VARCHAR(30)  NOT NULL COMMENT 'SUSPECTED_OCCUPY/GROUP_NO_ATTEND/OVERDUE_HOLDING/MANUAL',
    level          VARCHAR(10)  NOT NULL COMMENT 'HIGH/MID/LOW',
    reason         VARCHAR(200) NOT NULL COMMENT '生成依据的可读文案（含关键时刻）',
    evidence       TEXT         NOT NULL COMMENT '判定输入 JSON，无证据不落账',
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING/REMIND/RELEASED/VIOLATION/IGNORED',
    open_flag      TINYINT      NULL COMMENT 'PENDING=1，已处置=NULL（配合唯一索引）',
    handler_id     BIGINT       NULL COMMENT '处置人',
    handle_note    VARCHAR(200) NULL COMMENT '处置说明（必填）',
    photo_url      VARCHAR(200) NULL COMMENT '现场照片链接，可空',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时刻',
    handle_time    DATETIME     NULL COMMENT '处置时刻',
    UNIQUE KEY uk_patrol_open (seat_id, open_flag),
    KEY idx_patrol_status (status, level, create_time),
    KEY idx_patrol_floor (floor_id, open_flag, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='巡查任务';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'patrol.suspect-min',     '120', 'INT', 'PATROL', 1, '签到后连续使用无暂离多少分钟即登记「疑似占座」（PRD §6.5，默认 120）'),
    ('GLOBAL', '', 'patrol.suspect-min-left','30',  'INT', 'PATROL', 1, '疑似占座判定的另一个条件：预约剩余时间不少于该分钟数（快结束的不打扰）'),
    ('GLOBAL', '', 'patrol.group-absent-min','30',  'INT', 'PATROL', 1, '组队签到后多少分钟仍未确认入座即登记任务'),
    ('GLOBAL', '', 'patrol.escalate-min',    '30',  'INT', 'PATROL', 1, '同一座位同类型在多少分钟内被口头提醒过仍再犯，则新任务升为 HIGH');

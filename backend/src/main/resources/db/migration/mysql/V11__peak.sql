-- =====================================================================
-- V11：动态高峰状态与高峰调控参数（S5 / T-V2-18）
--
-- 与 h2/V11__peak.sql 同一份结构，只改方言：TIMESTAMP → DATETIME、
-- editable 用 1/0（TINYINT）。列语义、参数默认值、以及「为什么只存当前状态、
-- 为什么参数不放 t_peak_window」的说明都写在 h2 版头部注释里，两边不得漂。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_zone_peak_state (
    zone_id     BIGINT       NOT NULL PRIMARY KEY COMMENT '一个分区同时最多一条状态',
    since_time  DATETIME     NULL COMMENT '首次连续超阈时刻；回落即置 NULL',
    until_time  DATETIME     NULL COMMENT '动态高峰生效到何时；NULL 表示当前不在高峰',
    ratio       DECIMAL(5,2) NULL COMMENT '最近一次采样的占用率（百分数）',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次采样时刻'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分区动态高峰状态';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'peak.dynamic-threshold',       '90',    'INT',   'PEAK', 1, '动态高峰：分区占用率达到该百分比（0~100）开始计时'),
    ('GLOBAL', '', 'peak.compress-min',              '120',   'INT',   'PEAK', 1, '高峰时段单次时长上限（分钟）；静态窗口自带的 max_duration_min 更严时取其更严值'),
    ('GLOBAL', '', 'peak.dynamic-sustain-min',     '15',    'INT',   'PEAK', 1, '动态高峰：连续超阈多少分钟后才认定'),
    ('GLOBAL', '', 'peak.dynamic-hold-min',        '30',    'INT',   'PEAK', 1, '动态高峰：认定后至少保持多少分钟（回落不立即解除，防抖动）'),
    ('GLOBAL', '', 'peak.open-time',               '22:00', 'TIME',  'PEAK', 1, '高峰时段改为前一天这个时刻才开放'),
    ('GLOBAL', '', 'book.max-duration-teacher-min', '240',  'INT',   'BOOK', 1, '教师单次时长上限（分钟），用于覆盖分区上限');

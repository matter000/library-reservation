-- =====================================================================
-- V11：动态高峰状态与高峰调控参数（S5 / T-V2-18）
--
-- ★ 只存「当前状态」，不存采样流水：占用率每 5 分钟采一次，而判定要看
--   「连续 15 分钟都在阈值之上」，所以必须记住首次超阈的时刻（since_time）；
--   消费方（预约校验链第 5/6 步、余量卡片、平面图）只问一句「此刻这个区算不算高峰」，
--   答它 until_time 就够。做成流水表就是每次提问都重算一遍窗口，
--   还会诱导有人把这张表当成占用的第二真相 —— 占用的唯一真相仍是 t_reservation_slot（S4 教训）。
--
-- ★ 主键直接是 zone_id：一个分区同时最多一条状态，检测器每轮 UPSERT，
--   因此既不需要清理任务，也不会随运行时间长出多余行。
--
-- ★ 参数放 t_rule_config 而不是塞进 t_peak_window：t_peak_window 定义「哪几个小时算高峰」，
--   阈值 / 持续 / 保持时长是「怎么判定」，改前者是排班，改后者是风控调参，两者变更人不同。
--
-- ★ peak.dynamic-hold-min 存在的理由是防抖：占用率在阈值线附近来回摆时，
--   回落不立即解除，而是让已认定的高峰至少撑满保持时长，否则用户会看到
--   同一张桌子一分钟内「能约 4 小时 / 只能约 2 小时」来回翻。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_zone_peak_state (
    zone_id     BIGINT       NOT NULL PRIMARY KEY,          -- 一个分区最多一条状态
    since_time  TIMESTAMP    NULL,                          -- 首次连续超阈时刻；回落即置 NULL
    until_time  TIMESTAMP    NULL,                          -- 动态高峰生效到何时；NULL = 当前不在高峰
    ratio       DECIMAL(5,2) NULL,                          -- 最近一次采样的占用率（百分数），看板与排查用
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ===== 高峰调控参数（rgroup=PEAK，与 t_peak_window 的窗口定义分开放） =====
INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'peak.dynamic-threshold',       '90',    'INT',   'PEAK', TRUE, '动态高峰：分区占用率达到该百分比（0~100）开始计时'),
    ('GLOBAL', '', 'peak.compress-min',              '120',   'INT',   'PEAK', TRUE, '高峰时段单次时长上限（分钟）；静态窗口自带的 max_duration_min 更严时取其更严值'),
    ('GLOBAL', '', 'peak.dynamic-sustain-min',     '15',    'INT',   'PEAK', TRUE, '动态高峰：连续超阈多少分钟后才认定'),
    ('GLOBAL', '', 'peak.dynamic-hold-min',        '30',    'INT',   'PEAK', TRUE, '动态高峰：认定后至少保持多少分钟（回落不立即解除，防抖动）'),
    ('GLOBAL', '', 'peak.open-time',               '22:00', 'TIME',  'PEAK', TRUE, '高峰时段改为前一天这个时刻才开放'),
    ('GLOBAL', '', 'book.max-duration-teacher-min', '240',  'INT',   'BOOK', TRUE, '教师单次时长上限（分钟），用于覆盖分区上限');

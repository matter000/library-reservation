-- =====================================================================
-- V19：给"考试周"一个真正的承载点（与 h2/V19 同义，PRD §12-1、§12-3）
--
-- 日型以前只有 WORKDAY/WEEKEND/HOLIDAY，"考试周"在系统里不是状态，
-- 于是 §12-3 的"考试周 8 小时"从未生效。本版：
--   ① 播种 EXAM 开放档案（窗口先沿用工作日；延长到 07:00-23:00 需要网格锚点前移，另案）；
--   ② 新增规则 book.daily-cap-exam-min = 480，日型为 EXAM 时取代全馆单日上限。
-- =====================================================================

INSERT INTO t_open_profile (day_type, name, open_start, open_end, closed, enabled, remark) VALUES
    ('EXAM', '考试周 08:00-22:00', 16, 44, 0, 1,
     '单日累计放宽到 8 小时（book.daily-cap-exam-min）；窗口时刻仍按工作日，延长到 07:00-23:00 需先做时段网格锚点前移');

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'book.daily-cap-exam-min', '480', 'INT', 'BOOK', 1,
     '考试周单日累计预约上限（分钟，PRD §12-3 的 8 小时）；仅当日型为 EXAM 时取代 book.daily-cap-min');

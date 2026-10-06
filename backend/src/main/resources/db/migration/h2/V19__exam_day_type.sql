-- =====================================================================
-- V19：给"考试周"一个真正的承载点（T-V2-24 / B1 可落地的那一半，PRD §12-1、§12-3）
--
-- ★ 为什么需要这一版：
--   PRD 两处写着考试周的例外：§12-1「考试周 07:00–23:00」、§12-3「单日累计 6 小时（考试周 8 小时）」。
--   而日型枚举只有 WORKDAY / WEEKEND / HOLIDAY —— 也就是说"考试周"在系统里根本不是一个状态，
--   管理员只能手写备注，规则也无从判定今天算不算考试周。§12-3 那半句因此从未生效。
--
-- ★ 这一版只做能落地的部分，并且把两件事分开：
--   ① 单日累计放宽到 8 小时：完全可实现，新增规则 book.daily-cap-exam-min=480，
--      由日型 EXAM 触发（现在就能用开放日历把某天标成考试周）；
--   ② 窗口延长到 07:00–23:00：**不在本版**，它要求 30 分钟网格锚点从 slot 16=08:00 前移，
--      牵动全部时段运算与既有统计口径（DESIGN §14.2 单独立项）。
--      所以这里的考试周档案先沿用工作日窗口，并在 remark 里写明差异，
--      避免运营以为"考试周档案已经等于 PRD"。
-- =====================================================================

INSERT INTO t_open_profile (day_type, name, open_start, open_end, closed, enabled, remark) VALUES
    ('EXAM', '考试周 08:00-22:00', 16, 44, FALSE, TRUE,
     '单日累计放宽到 8 小时（book.daily-cap-exam-min）；窗口时刻仍按工作日，延长到 07:00-23:00 需先做时段网格锚点前移');

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'book.daily-cap-exam-min', '480', 'INT', 'BOOK', TRUE,
     '考试周单日累计预约上限（分钟，PRD §12-3 的 8 小时）；仅当日型为 EXAM 时取代 book.daily-cap-min');

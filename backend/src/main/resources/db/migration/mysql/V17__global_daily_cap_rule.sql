-- =====================================================================
-- V17：把「单日累计上限」补成全馆口径（与 h2/V17 同义，PRD §12-3）
--
-- 闸门以前只读 t_zone.daily_cap_min，"这个人今天一共坐了多久"从未被计算；
-- 这里新增全局规则 book.daily-cap-min（360 分钟 = 6 小时），
-- 并撤掉考研专座那个比全馆更宽、容易让人误读的 480（改为跟随全馆）。
-- 两道检查独立生效：分区口径管本区累计，全馆口径管当日总量。
-- =====================================================================

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'book.daily-cap-min', '360', 'INT', 'BOOK', 1,
     '单日累计预约上限（分钟，全馆口径，换分区也一样计入；PRD §12-3 的 6 小时）');

UPDATE t_zone SET daily_cap_min = NULL WHERE code = '3F-EXAM';

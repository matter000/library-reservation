-- =====================================================================
-- V21：暂离原因加 relaxable 标记（与 h2/V21 同义，B8 / PRD §12-7）
--
-- 分区只能放宽"标准上限级"的原因（就餐/上课）；接听/短休/取物仍按原因自己的上限，
-- 只可被分区收紧。原因表由 V7 用 SQL 播种，所以本迁移在两条建库路径下都生效（见 DESIGN §14.3）。
-- =====================================================================

ALTER TABLE t_leave_reason ADD COLUMN relaxable TINYINT(1) NOT NULL DEFAULT 0;

UPDATE t_leave_reason SET relaxable = 1 WHERE code IN ('MEAL', 'CLASS');

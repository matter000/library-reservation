-- =====================================================================
-- V2：角色升级（V1 的 USER / ADMIN → V2 的 STUDENT / SYS_ADMIN）
--      + t_user 新增 V2 列（信用分快照 / 学号 / 主动结束连续次数）
-- 与 H2 版差异：MySQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，
--              由 Flyway 保证脚本只执行一次，故直接 ADD COLUMN。
-- =====================================================================

UPDATE t_user SET role = 'STUDENT' WHERE role = 'USER';
UPDATE t_user SET role = 'SYS_ADMIN' WHERE role = 'ADMIN';

ALTER TABLE t_user ADD COLUMN credit_score INT NOT NULL DEFAULT 100 COMMENT '信用分快照（t_credit_log 的当前余额）';
ALTER TABLE t_user ADD COLUMN student_no VARCHAR(30) NULL COMMENT '学号 / 教工号';
ALTER TABLE t_user ADD COLUMN active_finish_streak INT NOT NULL DEFAULT 0 COMMENT '连续主动结束使用次数';

ALTER TABLE t_user ADD UNIQUE INDEX uk_user_student_no (student_no);

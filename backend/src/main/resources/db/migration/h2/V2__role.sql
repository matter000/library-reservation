-- =====================================================================
-- V2：角色升级（V1 的 USER / ADMIN → V2 的 STUDENT / SYS_ADMIN）
--      + t_user 新增 V2 列（信用分快照 / 学号 / 主动结束连续次数）
--
-- 说明：
--   1. 角色值迁移顺序敏感 —— 先 USER 后 ADMIN，两条互不冲突；
--   2. TEACHER / LIBRARIAN 由 DataInitializer 种子或管理员创建，历史数据无对应值；
--   3. credit_score 是 t_credit_log 的快照（V2 S3 起启用），student_no / active_finish_streak
--      为后续分片预留，先随本表一次性加列，避免多次重写 t_user。
-- =====================================================================

UPDATE t_user SET role = 'STUDENT' WHERE role = 'USER';
UPDATE t_user SET role = 'SYS_ADMIN' WHERE role = 'ADMIN';

ALTER TABLE t_user ADD COLUMN IF NOT EXISTS credit_score INT NOT NULL DEFAULT 100;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS student_no VARCHAR(30) NULL;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS active_finish_streak INT NOT NULL DEFAULT 0;

-- 学号可空：NULL 不参与唯一约束，允许历史账号暂不绑定学号
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_student_no ON t_user (student_no);

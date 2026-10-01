-- =====================================================================
-- V4：分区建模（S1 / T-V2-05）
-- 与 H2 版差异：自增列方言 / BOOLEAN→TINYINT(1) / TIMESTAMP→DATETIME /
--   索引与唯一约束写在表尾 / 表尾 ENGINE+CHARSET。
--   MySQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，故直接 ADD COLUMN
--   （V1 基线里 t_seat 无 zone_id，此处必然为新增）。
-- 回填语义与 H2 版一致：遗留库建 DEFAULT 分区并挂接座位；全新库交给种子。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_zone (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    floor_id          BIGINT       NOT NULL COMMENT '所属楼层',
    name              VARCHAR(50)  NOT NULL COMMENT '分区名',
    code              VARCHAR(20)  NOT NULL COMMENT '楼层内唯一编码，如 1F-SILENT',
    zone_type         VARCHAR(30)  NOT NULL COMMENT 'SILENT/SEMINAR/E_READING/LOUNGE/EXAM_PREP/TEACHER_ONLY',
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
    max_duration_min  INT          NOT NULL DEFAULT 240 COMMENT '单次时长上限（分钟）',
    daily_cap_min     INT          NULL COMMENT '单日累计上限；NULL = 不设分区上限',
    advance_days      INT          NOT NULL DEFAULT 3 COMMENT '可提前预约天数（含当天）',
    allow_discuss     VARCHAR(10)  NOT NULL DEFAULT 'NO' COMMENT 'NO / LOW / YES',
    allow_leave       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否允许暂离',
    leave_max_min     INT          NOT NULL DEFAULT 60 COMMENT '本区单次暂离封顶',
    leave_daily_limit INT          NOT NULL DEFAULT 3 COMMENT '本区每日暂离次数',
    allow_group       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否支持组队预约',
    group_required    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '研讨协作区：仅接受整组预约',
    visible_roles     VARCHAR(100) NOT NULL DEFAULT 'STUDENT,TEACHER,LIBRARIAN,SYS_ADMIN' COMMENT '可见角色 CSV',
    eligible_roles    VARCHAR(100) NOT NULL DEFAULT 'STUDENT,TEACHER,LIBRARIAN,SYS_ADMIN' COMMENT '可约角色 CSV',
    sort_order        INT          NOT NULL DEFAULT 0,
    remark            VARCHAR(200) NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_zone_floor_code (floor_id, code),
    KEY idx_zone_floor (floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分区（规则载体）';

ALTER TABLE t_seat ADD COLUMN zone_id BIGINT NULL COMMENT '所属分区';
CREATE INDEX idx_seat_zone ON t_seat (zone_id);

INSERT INTO t_zone (floor_id, name, code, zone_type, status, max_duration_min, advance_days,
                    allow_discuss, allow_leave, leave_max_min, leave_daily_limit,
                    allow_group, group_required, sort_order, remark)
SELECT f.id, '默认分区', 'DEFAULT', 'SILENT', 'OPEN', 240, 7,
       'NO', 1, 60, 3, 0, 0, 0, 'V1 升级自动创建，可在管理面改名或删除'
FROM t_floor f
WHERE NOT EXISTS (SELECT 1 FROM t_zone z WHERE z.floor_id = f.id);

UPDATE t_seat
SET zone_id = (SELECT z.id FROM t_zone z WHERE z.floor_id = t_seat.floor_id AND z.code = 'DEFAULT')
WHERE zone_id IS NULL;

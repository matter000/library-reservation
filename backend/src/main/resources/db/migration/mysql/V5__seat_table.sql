-- =====================================================================
-- V5：桌位建模 + 座位坐标唯一约束（S1 / T-V2-06）
-- 与 H2 版差异：自增列方言 / TIMESTAMP→DATETIME / 索引写在表尾 /
--   ALTER TABLE 不支持 IF NOT EXISTS（V1 基线无 table_id，此处必然为新增）。
-- 语义与 H2 版一致：uk_seat_pos 建索引失败即遗留数据重叠，须先用
--   docs/sql/fix-duplicate-seat-positions.sql 处置，不得跳过建索引。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_table (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    zone_id     BIGINT       NOT NULL COMMENT '所属分区',
    floor_id    BIGINT       NOT NULL COMMENT '冗余，便于按楼层统计',
    table_no    VARCHAR(20)  NOT NULL COMMENT '分区内唯一桌号，如 T05',
    seat_count  INT          NOT NULL DEFAULT 4 COMMENT '同桌座位数（4~6）',
    status      VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/MAINTENANCE/DISABLED',
    remark      VARCHAR(200) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_table_zone_no (zone_id, table_no),
    KEY idx_table_zone (zone_id),
    KEY idx_table_floor (floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='桌位（组队预约最小单位）';

ALTER TABLE t_seat ADD COLUMN table_id BIGINT NULL COMMENT '所属桌位，非组队区可空';
CREATE INDEX idx_seat_table ON t_seat (table_id);

-- ★ 本次缺陷修复的落点：遗留数据若重叠会在此失败（故意的）
CREATE UNIQUE INDEX uk_seat_pos ON t_seat (floor_id, row_no, col_no);

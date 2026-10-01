-- =====================================================================
-- V6：30 分钟时段 + 开放档案 / 开放日历 / 高峰窗口（S1 / T-V2-07）
-- 与 H2 版差异：自增列方言 / BOOLEAN→TINYINT(1) / TIMESTAMP→DATETIME /
--   索引写在表尾 / 表尾 ENGINE+CHARSET。
-- 语义与 H2 版一致：先把 t_reservation 与 t_reservation_slot 的时段序号 ×2，
-- 再落开放档案；乘二是单射，不会撞唯一索引。
-- =====================================================================

UPDATE t_reservation SET start_slot = start_slot * 2, end_slot = end_slot * 2;
UPDATE t_reservation_slot SET slot_index = slot_index * 2;

CREATE TABLE IF NOT EXISTS t_open_profile (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    day_type     VARCHAR(20)  NOT NULL COMMENT 'WORKDAY / WEEKEND / HOLIDAY',
    name         VARCHAR(50)  NOT NULL COMMENT '档案名称',
    open_start   INT          NOT NULL COMMENT '起始时段序号（含）',
    open_end     INT          NOT NULL COMMENT '结束时段的开区间边界',
    closed       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '该日型整体闭馆',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    remark       VARCHAR(200) NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profile_day_type (day_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='开放档案（按日型）';

CREATE TABLE IF NOT EXISTS t_open_calendar (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    open_date    DATE         NOT NULL COMMENT '具体日期',
    day_type     VARCHAR(20)  NULL COMMENT '覆盖日型，空表示沿用',
    open_start   INT          NULL COMMENT '覆盖起始时段，空表示沿用',
    open_end     INT          NULL COMMENT '覆盖结束时段，空表示沿用',
    closed       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '临时闭馆',
    remark       VARCHAR(200) NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_calendar_date (open_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='开放日历（单日覆盖）';

CREATE TABLE IF NOT EXISTS t_peak_window (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY,
    scope             VARCHAR(20)  NOT NULL DEFAULT 'GLOBAL' COMMENT 'GLOBAL / FLOOR / ZONE',
    floor_id          BIGINT       NULL,
    zone_id           BIGINT       NULL,
    name              VARCHAR(50)  NOT NULL,
    day_types         VARCHAR(60)  NOT NULL DEFAULT 'WORKDAY' COMMENT 'CSV 日型',
    start_slot        INT          NOT NULL,
    end_slot          INT          NOT NULL,
    max_duration_min  INT          NULL COMMENT '高峰期间时长压缩上限',
    advance_open_slot INT          NULL COMMENT '高峰提前开放的时段格',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    remark            VARCHAR(200) NULL,
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_peak_scope (scope, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='高峰窗口';

INSERT INTO t_open_profile (day_type, name, open_start, open_end, closed, enabled, remark) VALUES
    ('WORKDAY',  '工作日 08:00-22:00', 16, 44, 0, 1, '与 V1 完全一致的默认窗口'),
    ('WEEKEND',  '周末 09:00-21:00',   18, 42, 0, 1, '周末缩短，避免夜间空场'),
    ('HOLIDAY',  '节假日 10:00-18:00', 20, 36, 0, 1, '法定节假日窗口');

-- =====================================================================
-- V7：暂离（S2 / T-V2-09）
-- 与 H2 版差异：自增列方言 / BOOLEAN→TINYINT(1) / TIMESTAMP→DATETIME /
--   SMALLINT 保留 / 索引写在表尾 / 表尾 ENGINE+CHARSET。
-- 语义与 H2 版一致：uk_leave_open (reservation_id, open_flag) 靠
--   「NULL 互不相等」保证一条预约至多一条 OPEN 暂离，并发由索引兜住。
-- need_note 的取舍同 H2 版注释：CLASS 与 OTHER 必须填事由。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_leave_reason (
    code         VARCHAR(30)  NOT NULL PRIMARY KEY COMMENT 'MEAL/CLASS/PHONE/REST/PICKUP/OTHER',
    name         VARCHAR(50)  NOT NULL COMMENT '原因名称',
    max_min      INT          NOT NULL COMMENT '单次最长暂离分钟',
    daily_limit  INT          NOT NULL DEFAULT 3 COMMENT '该原因每日次数上限',
    need_note    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '必须填事由',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order   INT          NOT NULL DEFAULT 0,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='暂离原因字典';

CREATE TABLE IF NOT EXISTS t_leave_record (
    id                 BIGINT       AUTO_INCREMENT PRIMARY KEY,
    reservation_id     BIGINT       NOT NULL COMMENT '所属预约',
    user_id            BIGINT       NOT NULL COMMENT '冗余便于按人按日计数',
    seat_id            BIGINT       NOT NULL COMMENT '冗余便于巡查与统计直接定位座位',
    reason_code        VARCHAR(30)  NOT NULL,
    note               VARCHAR(100) NULL COMMENT '事由，need_note 的原因必填',
    start_time         DATETIME     NOT NULL COMMENT '登记时刻（按自然日计次即按此列）',
    planned_end_time   DATETIME     NOT NULL COMMENT '应返回时刻（双重截断后的结果）',
    actual_return_time DATETIME     NULL COMMENT '实际返回时刻',
    status             VARCHAR(20)  NOT NULL COMMENT 'OPEN / RETURNED / TIMEOUT',
    open_flag          SMALLINT     NULL COMMENT '★ OPEN 时 =1，结束置 NULL',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_leave_open (reservation_id, open_flag),
    KEY idx_leave_user_date (user_id, start_time),
    KEY idx_leave_status (status, planned_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='暂离记录';

INSERT INTO t_leave_reason (code, name, max_min, daily_limit, need_note, enabled, sort_order) VALUES
    ('MEAL',   '就餐',     60, 3, 0, 1, 1),
    ('CLASS',  '上课',     60, 3, 1, 1, 2),
    ('PHONE',  '接电话',   20, 3, 0, 1, 3),
    ('REST',   '短暂休息', 20, 3, 0, 1, 4),
    ('PICKUP', '取物',     15, 3, 0, 1, 5),
    ('OTHER',  '其他',     15, 3, 1, 1, 6);

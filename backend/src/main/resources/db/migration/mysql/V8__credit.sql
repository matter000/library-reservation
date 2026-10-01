-- =====================================================================
-- V8：违规 / 信用账本 / 处罚阶梯（S3 / T-V2-12）
-- 与 H2 版差异：自增列方言 / BOOLEAN→TINYINT(1) / TIMESTAMP→DATETIME /
--   CLOB→LONGTEXT / 索引写在表尾 / 字符串拼接用 CONCAT / 表尾 ENGINE+CHARSET。
-- 语义与 H2 版一致：三张表 + CREDIT 规则键 + 历史爽约只折算分数与账本（不伪造违规证据）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_violation (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL COMMENT '违规主体',
    reservation_id BIGINT       NULL COMMENT '现场占座等无预约时为 NULL',
    type           VARCHAR(30)  NOT NULL COMMENT 'NO_SHOW/LEAVE_TIMEOUT/GROUP_ABSENT/LEADER_ABSENT/ON_SITE_OCCUPY/NO_CANCEL',
    score_delta    INT          NOT NULL COMMENT '负数',
    evidence       LONGTEXT     NOT NULL COMMENT '★ 无证据不得裁定，故 NOT NULL',
    status         VARCHAR(20)  NOT NULL COMMENT 'ACTIVE / APPEALING / REVOKED',
    created_by     VARCHAR(20)  NOT NULL COMMENT 'SYSTEM / LIBRARIAN',
    creator_id     BIGINT       NULL,
    revoke_time    DATETIME     NULL,
    revoke_reason  VARCHAR(200) NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_viol_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='违规记录';

CREATE TABLE IF NOT EXISTS t_credit_log (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    kind          VARCHAR(20)  NOT NULL COMMENT 'PENALTY/WEEKLY_BONUS/STREAK_BONUS/RESTORE/INIT',
    delta         INT          NOT NULL,
    balance_after INT          NOT NULL COMMENT '★ 必填，用于 sum(delta) 与快照对账',
    ref_type      VARCHAR(20)  NULL,
    ref_id        BIGINT       NULL,
    reason        VARCHAR(100) NULL,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_clog_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='信用流水（只追加）';

CREATE TABLE IF NOT EXISTS t_penalty (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    type         VARCHAR(30)  NOT NULL COMMENT 'PEAK_BAN/BOOK_BAN_3D/BOOK_BAN_7D/SEMESTER_BAN',
    start_time   DATETIME     NOT NULL,
    end_time     DATETIME     NULL COMMENT 'SEMESTER_BAN / PEAK_BAN 靠解除或回分，无固定 end_time',
    status       VARCHAR(20)  NOT NULL COMMENT 'ACTIVE / RELEASED',
    reason       VARCHAR(100) NULL,
    released_by  BIGINT       NULL,
    release_note VARCHAR(200) NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_penalty_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='处罚阶梯';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'credit.enforce_enabled', 'true',  'BOOL', 'CREDIT', 1, '信用闸门总开关：false 时只记录违规与扣分，不阻断预约（演示逃生阀）'),
    ('GLOBAL', '', 'credit.init-score',      '100',   'INT',  'CREDIT', 1, '初始信用分'),
    ('GLOBAL', '', 'credit.peak-ban-under',  '90',    'INT',  'CREDIT', 1, '低于此分 → 高峰时段禁约'),
    ('GLOBAL', '', 'credit.ban-3d-under',    '70',    'INT',  'CREDIT', 1, '低于此分 → 暂停预约 3 天'),
    ('GLOBAL', '', 'credit.ban-7d-under',    '50',    'INT',  'CREDIT', 1, '低于此分 → 暂停预约 7 天'),
    ('GLOBAL', '', 'credit.semester-under',   '1',    'INT',  'CREDIT', 1, '低于此分（即 0 分）→ 暂停至学期末，仅系统管理员可解除'),
    ('GLOBAL', '', 'credit.ban-3d-days',     '3',     'INT',  'CREDIT', 1, '暂停预约 3 天的天数'),
    ('GLOBAL', '', 'credit.ban-7d-days',     '7',     'INT',  'CREDIT', 1, '暂停预约 7 天的天数'),
    ('GLOBAL', '', 'credit.streak-bonus',    '5',     'INT',  'CREDIT', 1, '连续主动结束满 N 次的恢复加分'),
    ('GLOBAL', '', 'credit.streak-size',     '5',     'INT',  'CREDIT', 1, '连续主动结束多少次给一次恢复加分');

UPDATE t_user
   SET credit_score = GREATEST(1, 100 - no_show_count * 10)
 WHERE no_show_count > 0;

INSERT INTO t_credit_log (user_id, kind, delta, balance_after, ref_type, reason)
    SELECT u.id, 'INIT', u.credit_score, u.credit_score, 'MIGRATION',
           CASE WHEN u.no_show_count > 0
                THEN CONCAT('初始信用分（含 V1 爽约折算：no_show_count=', u.no_show_count, '）')
                ELSE '初始信用分'
           END
      FROM t_user u;

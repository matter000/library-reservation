-- =====================================================================
-- V3：规则配置中心 + 审计日志（S0）
-- 与 H2 版差异：自增列方言 / TIMESTAMP→DATETIME / CLOB→TEXT / 表尾 ENGINE+CHARSET
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_rule_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope       VARCHAR(20)  NOT NULL COMMENT 'GLOBAL / ZONE / DAY_TYPE',
    scope_id    VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '作用域对象 ID；GLOBAL 为空串（不用 NULL，否则唯一索引失效）',
    rkey        VARCHAR(50)  NOT NULL COMMENT '规则键',
    rvalue      VARCHAR(200) NOT NULL COMMENT '规则值（按 value_type 解析）',
    value_type  VARCHAR(10)  NOT NULL DEFAULT 'STRING' COMMENT 'INT / BOOL / TIME / STRING / INT_LIST',
    rgroup      VARCHAR(30)  NOT NULL DEFAULT 'BOOK' COMMENT 'OPEN/BOOK/LEAVE/CREDIT/PEAK/PATROL',
    editable    TINYINT(1)   NOT NULL DEFAULT 1,
    description VARCHAR(200) NULL,
    updated_by  BIGINT       NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule (scope, scope_id, rkey),
    KEY idx_rule_group (rgroup)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='规则配置';

CREATE TABLE IF NOT EXISTS t_audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id    BIGINT      NULL,
    actor_role  VARCHAR(20) NULL,
    action      VARCHAR(50) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    target_id   VARCHAR(30) NULL,
    detail      TEXT        NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_time (create_time),
    KEY idx_audit_actor (actor_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作审计日志';

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'book.check-in-before-min', '15', 'INT', 'BOOK', 1, '签到窗口：预约开始前多少分钟内可签到'),
    ('GLOBAL', '', 'book.check-in-after-min',  '15', 'INT', 'BOOK', 1, '签到窗口：预约开始后多少分钟内仍可签到，超时置 NO_SHOW'),
    ('GLOBAL', '', 'book.advance-days',        '7',  'INT', 'BOOK', 1, '可提前预约天数（含当天）'),
    ('GLOBAL', '', 'book.default-duration',    '2',  'INT', 'BOOK', 1, '预约默认时长（小时）'),
    ('GLOBAL', '', 'book.allowed-durations',   '1,2,3,4', 'INT_LIST', 'BOOK', 1, '可选时长列表（小时）');

-- =====================================================================
-- V9：申诉闭环（S3 / T-V2-13）
-- 与 H2 版差异：自增列方言 / TIMESTAMP→DATETIME / 索引写在表尾 / 表尾 ENGINE+CHARSET。
-- 语义与 H2 版一致：一张 t_appeal + 「一条违规一次申诉」的唯一索引兜底。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_appeal (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    violation_id BIGINT       NOT NULL COMMENT '一条违规只能有一条申诉',
    user_id      BIGINT       NOT NULL COMMENT '冗余存一份：学生面「我的申诉」不必回头 join 违规',
    reason       VARCHAR(500) NOT NULL COMMENT '学生事由，必填',
    status       VARCHAR(20)  NOT NULL COMMENT 'PENDING/APPROVED/REJECTED/ESCALATED/FINAL_JUDGED',
    handler_id   BIGINT       NULL COMMENT '裁定人（终审同样记）',
    handler_role VARCHAR(20)  NULL COMMENT 'LIBRARIAN / SYS_ADMIN，用来区分复核与终审',
    reply        VARCHAR(500) NULL COMMENT '裁定说明；驳回时必填',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handle_time  DATETIME     NULL,
    UNIQUE KEY uk_appeal_violation (violation_id),
    KEY idx_appeal_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='违规申诉';

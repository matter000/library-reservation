-- =====================================================================
-- V10：组队预约（S4 / T-V2-15）
-- 与 H2 版差异：自增列方言 / TIMESTAMP→DATETIME / 索引写在表尾 /
--   ALTER TABLE 不支持 IF NOT EXISTS（V1 基线 t_reservation 无 group_id，此处必然为新增）。
-- 语义与 H2 版一致：两张组队表 + t_reservation.group_id 关联键；不新建占用表；不建 t_zone_grant。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_group (
    id                 BIGINT       AUTO_INCREMENT PRIMARY KEY,
    leader_id          BIGINT       NOT NULL COMMENT '组长',
    floor_id           BIGINT       NOT NULL,
    zone_id            BIGINT       NOT NULL,
    table_id           BIGINT       NULL COMMENT '整桌下单必填；NULL 表示按分区散座组',
    reserve_date       DATE         NOT NULL,
    start_slot         INT          NOT NULL COMMENT '30 分钟格序号（含）',
    end_slot           INT          NOT NULL COMMENT '30 分钟格序号（不含）',
    invite_code        VARCHAR(10)  NULL COMMENT '6 位邀请码，10 分钟有效',
    invite_expire_time DATETIME     NULL,
    min_members        INT          NOT NULL COMMENT '人数不足到锁定时刻则整队 FAILED',
    max_members        INT          NOT NULL,
    lock_time          DATETIME     NOT NULL COMMENT '开始前 30 分钟，此后禁止增减成员',
    status             VARCHAR(20)  NOT NULL COMMENT 'FORMING/LOCKED/RESERVED/CHECKED_IN/COMPLETED/CANCELLED/FAILED',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_invite (invite_code),
    KEY idx_group_leader (leader_id, status),
    KEY idx_group_status (status, lock_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='组队预约队伍';

CREATE TABLE IF NOT EXISTS t_group_member (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    group_id      BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    member_role   VARCHAR(10)  NOT NULL COMMENT 'LEADER / MEMBER',
    invite_status VARCHAR(20)  NOT NULL COMMENT 'INVITED/CONFIRMED/DECLINED/EXPIRED',
    confirm_time  DATETIME     NULL,
    seat_id       BIGINT       NULL COMMENT '锁定后实际占住的座位；未分配为 NULL',
    attend_status VARCHAR(20)  NOT NULL COMMENT 'UNKNOWN/PRESENT/ABSENT（T-V2-16 用）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_user (group_id, user_id),
    KEY idx_group_member_user (user_id, invite_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='队伍成员';

ALTER TABLE t_reservation ADD COLUMN group_id BIGINT NULL COMMENT '同一次整桌下单的关联键，不参与占用判定';
CREATE INDEX idx_resv_group ON t_reservation (group_id);

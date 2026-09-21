-- =====================================================================
-- 图书馆座位预约系统 —— MySQL 8 建表脚本
-- 与 H2 版的差异仅 3 处：自增列方言 / DATETIME / 表尾 ENGINE+CHARSET+COMMENT
-- 说明：MySQL 不支持 CREATE INDEX IF NOT EXISTS，故索引为普通 CREATE INDEX；
--       首次建库执行一次即可（重复执行会报 Duplicate key name，属预期）。
-- =====================================================================

-- ============ 用户表（普通用户 + 管理员共用） ============
CREATE TABLE IF NOT EXISTS t_user (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    username       VARCHAR(50)  NOT NULL COMMENT '登录账号，唯一',
    password_hash  VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    real_name      VARCHAR(50)  NOT NULL COMMENT '真实姓名（管理员可见）',
    phone          VARCHAR(20)  NULL COMMENT '手机号（管理员端脱敏展示）',
    role           VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',
    no_show_count  INT          NOT NULL DEFAULT 0 COMMENT '超时未签到次数',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户表';

-- ============ 楼层表 ============
CREATE TABLE IF NOT EXISTS t_floor (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    name         VARCHAR(50)  NOT NULL COMMENT '楼层名称',
    code         VARCHAR(20)  NOT NULL COMMENT '楼层编号，唯一',
    description  VARCHAR(200) NULL COMMENT '描述',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '排序号，升序',
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_floor_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='楼层表';

-- ============ 座位表（★ 不存储「是否被占用」，占用由预约派生） ============
CREATE TABLE IF NOT EXISTS t_seat (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    floor_id    BIGINT       NOT NULL COMMENT '所属楼层',
    seat_no     VARCHAR(20)  NOT NULL COMMENT '座位编号，楼层内唯一',
    row_no      INT          NOT NULL COMMENT '行号，从 1 开始',
    col_no      INT          NOT NULL COMMENT '列号，从 1 开始',
    zone        VARCHAR(20)  NULL COMMENT '区域名',
    status      VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/MAINTENANCE/DISABLED',
    remark      VARCHAR(200) NULL COMMENT '备注',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_seat_floor_no (floor_id, seat_no),
    KEY idx_seat_floor (floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='座位表';

-- ============ 预约主表 ============
CREATE TABLE IF NOT EXISTS t_reservation (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    user_id              BIGINT      NOT NULL COMMENT '预约人',
    seat_id              BIGINT      NOT NULL COMMENT '座位',
    floor_id             BIGINT      NOT NULL COMMENT '冗余楼层，便于统计',
    reserve_date         DATE        NOT NULL COMMENT '预约日期（不跨天）',
    start_slot           INT         NOT NULL COMMENT '起始时段序号 8~21',
    end_slot             INT         NOT NULL COMMENT '结束时段序号（开区间）',
    status               VARCHAR(20) NOT NULL COMMENT 'CONFIRMED/CHECKED_IN/COMPLETED/CANCELLED/NO_SHOW/ADMIN_CANCELLED',
    check_in_time        DATETIME    NULL COMMENT '签到时间',
    cancel_time          DATETIME    NULL COMMENT '取消/释放时间',
    admin_cancel_reason  VARCHAR(100) NULL COMMENT '管理员强制释放原因',
    create_time          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_resv_user_status (user_id, status),
    KEY idx_resv_date_status (reserve_date, status),
    KEY idx_resv_seat_date (seat_id, reserve_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预约主表';

-- ============ 占用明细表（★ 并发防重核心；只保存「当前有效」的占用行） ============
CREATE TABLE IF NOT EXISTS t_reservation_slot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    reservation_id  BIGINT NOT NULL COMMENT '所属预约',
    seat_id         BIGINT NOT NULL COMMENT '座位（冗余）',
    reserve_date    DATE   NOT NULL COMMENT '日期',
    slot_index      INT    NOT NULL COMMENT '时段序号 8~21',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_slot_seat_date_idx (seat_id, reserve_date, slot_index),
    KEY idx_slot_date_idx (reserve_date, slot_index),
    KEY idx_slot_resv (reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='占用明细表';

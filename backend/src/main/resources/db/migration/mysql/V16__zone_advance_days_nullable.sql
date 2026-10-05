-- =====================================================================
-- V16：让「分区没配本区口径」这件事可以被表达（与 h2/V16 同义）
--
-- advance_days 原本是 NOT NULL DEFAULT 3，导致"跟随全馆"这个状态无法落库，
-- 全局规则 book.advance-days 在预约闸门里成了永远读不到的死配置。
-- 这里只放开约束、不改数据，方式与 V4 起就允许 NULL 的 daily_cap_min 一致。
-- =====================================================================

ALTER TABLE t_zone
    MODIFY COLUMN advance_days INT NULL DEFAULT NULL COMMENT '可提前预约天数（含当天）；NULL = 跟随全馆规则';

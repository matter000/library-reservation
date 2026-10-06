-- =====================================================================
-- V24：高峰降级的两个参数进规则中心（PRD §13.4「预约接口排队而非直接报错」）
--
-- ★ 为什么必须可配而不是常量：这一档里 0 就是"暂时关闭预约"的运营开关
--   （查看余座照常可用），而开馆日的瞬时高峰到底能吞多少并发只有馆方知道；
--   写死就变成"想调就得发版"。
-- ★ 本表由 SQL 播种（V3/V11…），所以迁移在"空库冷启动"与"老库增量"两条路径都会生效
--   —— 与 B9 那类 Java 种子表相反，这里不需要动 DataInitializer。
-- ★ 消费点唯一：RuleConfigService#degradeMaxInflight / #degradeWaitMs → BookingDegradeGuard，
--   只挂在 POST /api/reservations 上（读接口不取许可）。
-- =====================================================================

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'peak.degrade-max-inflight', '16', 'INT', 'PEAK', TRUE,
     '预约接口允许的同时在途请求数（PRD §13.4 高峰降级）；超过则排队等待，等不到返回 4291；0 等价于暂停预约但不影响查看余座');

INSERT INTO t_rule_config (scope, scope_id, rkey, rvalue, value_type, rgroup, editable, description) VALUES
    ('GLOBAL', '', 'peak.degrade-wait-ms', '2000', 'INT', 'PEAK', TRUE,
     '过载时单个预约请求的最长排队等待（毫秒）；超时才返回 4291，避免把过载直接报成 500');

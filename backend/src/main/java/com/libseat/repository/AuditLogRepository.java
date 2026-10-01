package com.libseat.repository;

import com.libseat.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 审计日志数据访问。
 *
 * <p>查询走 {@link JpaSpecificationExecutor} 动态条件 —— 而不是
 * {@code where (:from is null or ...)} 这种写法：后者在 H2 / MySQL 上对 null 时间参数的
 * 类型推断行为不一致，属于跨库必踩的坑。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}

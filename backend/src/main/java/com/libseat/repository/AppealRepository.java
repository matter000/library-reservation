package com.libseat.repository;

import com.libseat.entity.Appeal;
import com.libseat.enums.AppealStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 申诉数据访问。
 *
 * <p>队列查询用「两个方法 + 服务层分支」而不是
 * {@code where (:status is null or a.status = :status)}：后者在 H2 / MySQL 上对 null 参数的
 * 类型推断行为不一致，是本项目已记录的跨库坑（见 {@link AuditLogRepository} 的同款说明）。
 */
@Repository
public interface AppealRepository extends JpaRepository<Appeal, Long> {

    /**
     * 取某条违规的申诉（一条违规最多一条，{@code uk_appeal_violation} 保证）。
     *
     * @param violationId 违规 ID
     * @return 申诉
     */
    Optional<Appeal> findByViolationId(Long violationId);

    /**
     * 某人的申诉列表，新的在前。
     *
     * @param userId 用户 ID
     * @return 申诉列表
     */
    List<Appeal> findByUserIdOrderByIdDesc(Long userId);

    /**
     * 某状态的队列，旧的在前（先提先得，免得馆员永远从最新一条往下翻）。
     *
     * @param status 状态
     * @return 申诉列表
     */
    List<Appeal> findByStatusOrderByIdAsc(AppealStatus status);

    /**
     * 全量队列（馆员选择「不限状态」时用），旧的在前。
     *
     * @return 申诉列表
     */
    List<Appeal> findAllByOrderByIdAsc();
}

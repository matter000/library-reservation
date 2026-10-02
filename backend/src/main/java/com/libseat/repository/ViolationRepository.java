package com.libseat.repository;

import com.libseat.entity.Violation;
import com.libseat.enums.ViolationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 违规记录数据访问层。
 */
@Repository
public interface ViolationRepository extends JpaRepository<Violation, Long> {

    /**
     * 某人的违规列表（新→旧）。
     *
     * @param userId 用户 ID
     * @return 违规列表
     */
    List<Violation> findByUserIdOrderByIdDesc(Long userId);

    /**
     * 全馆违规列表（新→旧），供馆员面「违规查询」（F8）使用。
     *
     * @return 违规列表
     */
    List<Violation> findAllByOrderByIdDesc();

    /**
     * 某人某时间窗内的违规数（周恢复任务判定「上一自然周有无新增违规」）。
     *
     * @param userId 用户 ID
     * @param from   起点（含）
     * @param to     终点（不含）
     * @return 条数
     */
    long countByUserIdAndCreateTimeGreaterThanEqualAndCreateTimeLessThan(Long userId,
                                                                        LocalDateTime from, LocalDateTime to);

    /**
     * 某人处于某状态的违规条数。
     *
     * @param userId 用户 ID
     * @param status 状态
     * @return 条数
     */
    long countByUserIdAndStatus(Long userId, ViolationStatus status);
}

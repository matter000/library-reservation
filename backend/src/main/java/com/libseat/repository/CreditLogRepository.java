package com.libseat.repository;

import com.libseat.entity.CreditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 信用流水数据访问层。
 */
@Repository
public interface CreditLogRepository extends JpaRepository<CreditLog, Long> {

    /**
     * 某人全部流水（按时间正序，用于展示「分数是怎么走到今天的」）。
     *
     * @param userId 用户 ID
     * @return 流水列表
     */
    List<CreditLog> findByUserIdOrderByCreateTimeAscIdAsc(Long userId);

    /**
     * ★ 对账用：流水变化量之和。无流水时为 null，由调用方折算成 0。
     *
     * @param userId 用户 ID
     * @return delta 之和
     */
    @Query("select coalesce(sum(c.delta), 0) from CreditLog c where c.userId = :userId")
    int sumDelta(@Param("userId") Long userId);
}

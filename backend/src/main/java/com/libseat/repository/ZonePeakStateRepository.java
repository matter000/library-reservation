package com.libseat.repository;

import com.libseat.entity.ZonePeakState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分区动态高峰状态数据访问层。
 *
 * <p>★ 不提供「按时间范围查历史」的方法：这张表只有当前状态，
 * 谁需要历史就该去别的链路（巡查看板 / 统计）取，别把状态表当流水表用。
 */
@Repository
public interface ZonePeakStateRepository extends JpaRepository<ZonePeakState, Long> {

    /**
     * 此刻仍处于动态高峰的分区（余量卡片与平面图批量标记用）。
     *
     * @param now 当前时刻（截止时刻晚于它的才算生效中）
     * @return 状态列表
     */
    List<ZonePeakState> findByUntilTimeAfter(LocalDateTime now);
}

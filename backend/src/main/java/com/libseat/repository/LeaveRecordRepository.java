package com.libseat.repository;

import com.libseat.entity.LeaveRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 暂离记录数据访问层。
 */
@Repository
public interface LeaveRecordRepository extends JpaRepository<LeaveRecord, Long> {

    /**
     * 取某条预约当前 OPEN 的暂离（{@code open_flag=1} 至多一条，由唯一索引保证）。
     *
     * @param reservationId 预约 ID
     * @param openFlag      固定传 1
     * @return 活跃暂离
     */
    Optional<LeaveRecord> findByReservationIdAndOpenFlag(Long reservationId, Integer openFlag);

    /**
     * 按自然日统计某人的暂离次数（口径是 {@code start_time} 落在区间内，DESIGN §5.2 第 4 步）。
     *
     * @param userId 用户 ID
     * @param from   当日起点（含）
     * @param to     次日起点（不含）
     * @return 次数
     */
    long countByUserIdAndStartTimeGreaterThanEqualAndStartTimeLessThan(Long userId, LocalDateTime from, LocalDateTime to);
}

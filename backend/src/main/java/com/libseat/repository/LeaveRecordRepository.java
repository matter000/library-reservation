package com.libseat.repository;

import com.libseat.entity.LeaveRecord;
import com.libseat.enums.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    /**
     * 某人在某自然日内、按原因各自的次数（PRD §6.2 表：就餐每日 2 次、接听/短休每日 3 次）。
     *
     * <p>与「分区总数上限」是两条独立规则，不要再拿它去夹总数（V15 的说明）。
     *
     * @param userId     用户 ID
     * @param reasonCode 原因码
     * @param from       当日起点（含）
     * @param to         次日起点（不含）
     * @return 次数
     */
    long countByUserIdAndReasonCodeAndStartTimeGreaterThanEqualAndStartTimeLessThan(
            Long userId, String reasonCode, LocalDateTime from, LocalDateTime to);

    /**
     * 某人某日内各原因已用次数（原因字典一次问完，不在原因循环里逐条 count）。
     *
     * @param userId 用户 ID
     * @param from   当日起点（含）
     * @param to     次日起点（不含）
     * @return 每行 {@code [reasonCode, count]}
     */
    @Query("select l.reasonCode, count(l) from LeaveRecord l "
            + "where l.userId = :userId and l.startTime >= :from and l.startTime < :to "
            + "group by l.reasonCode")
    List<Object[]> countTodayByReason(@Param("userId") Long userId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /**
     * ★ 占用派生用：取「某日期某时段」上仍处于 OPEN 的暂离。
     *
     * <p>与 {@code findOccupiedByDateAndSlot} 同样一次查完，平面图整层只需两次查询；
     * 不在座位循环里按预约逐条查暂离（那会把单点派生退化成 N+1）。
     *
     * @param date     日期
     * @param slot     时段序号
     * @param openFlag 固定传 1
     * @return 每行 {@code [seatId, startTime, plannedEndTime]}
     */
    @Query("select l.seatId, l.startTime, l.plannedEndTime "
            + "from LeaveRecord l, ReservationSlot s "
            + "where s.reservation.id = l.reservationId "
            + "and s.reserveDate = :date and s.slotIndex = :slot and l.openFlag = :openFlag")
    List<Object[]> findOpenLeavesByDateAndSlot(@Param("date") LocalDate date,
                                               @Param("slot") int slot,
                                               @Param("openFlag") Integer openFlag);

    /**
     * 批量取若干预约的 OPEN 暂离（「我的预约」列表一次查完，不在循环里逐条查）。
     *
     * @param reservationIds 预约 ID 集合
     * @param openFlag       固定传 1
     * @return OPEN 暂离列表
     */
    List<LeaveRecord> findByReservationIdInAndOpenFlag(Collection<Long> reservationIds, Integer openFlag);

    /**
     * 超时未归的暂离（DESIGN §5.3 扫描入口）。
     *
     * <p>只挑 {@code open_flag=1} 的行：处理完会把 open_flag 置回 NULL，
     * 因此同一轮之后不会再被选中，重复扫描天然幂等。
     *
     * @param status   OPEN
     * @param openFlag 固定传 1
     * @param before   应归时刻早于此值即算超时
     * @return 超时记录
     */
    List<LeaveRecord> findByStatusAndOpenFlagAndPlannedEndTimeBefore(
            LeaveStatus status, Integer openFlag, LocalDateTime before);

    /**
     * 还剩 within 分钟内到期的暂离（站内提醒扫描入口，T-V2-20）。
     *
     * <p>下界取 {@code from}（含）而不是更早：应归时刻已过的记录归超时扫描管，
     * 那里给的是「已违规」而不是「快到期」。
     *
     * @param status   OPEN
     * @param openFlag 固定传 1
     * @param from     应归时刻下界
     * @param to       应归时刻上界
     * @return 即将到期的记录
     */
    List<LeaveRecord> findByStatusAndOpenFlagAndPlannedEndTimeBetween(
            LeaveStatus status, Integer openFlag, LocalDateTime from, LocalDateTime to);

    /**
     * 全馆此刻暂离中的人数（监控大屏的「暂离保留」指标，T-V2-22）。
     *
     * <p>只要条数不要明细，所以用 count 而不是 findAll ——
     * 大屏每 30 秒刷一次，别为了一个数字把整张暂离表搬进内存。
     *
     * @param status   OPEN
     * @param openFlag 固定传 1
     * @return 条数
     */
    long countByStatusAndOpenFlag(LeaveStatus status, Integer openFlag);
}

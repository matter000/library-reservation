package com.libseat.repository;

import com.libseat.entity.Reservation;
import com.libseat.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 预约数据访问层。
 */
@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long>,
        JpaSpecificationExecutor<Reservation> {

    /**
     * 查询某用户在指定状态下的全部预约。
     *
     * @param userId   用户 ID
     * @param statuses 状态集合
     * @return 预约列表
     */
    List<Reservation> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    /**
     * 统计某日期的全部预约数（含已取消）。
     *
     * @param date 日期
     * @return 预约数
     */
    int countByReserveDate(LocalDate date);

    /**
     * 统计某状态的预约数（全局，用于「当前在场」）。
     *
     * @param status 状态
     * @return 预约数
     */
    long countByStatus(ReservationStatus status);

    /**
     * 统计某日期某状态的预约数。
     *
     * @param date   日期
     * @param status 状态
     * @return 预约数
     */
    long countByReserveDateAndStatus(LocalDate date, ReservationStatus status);

    /**
     * 查询某日期处于指定状态的预约。
     *
     * @param date     日期
     * @param statuses 状态集合
     * @return 预约列表
     */
    List<Reservation> findByReserveDateAndStatusIn(LocalDate date, Collection<ReservationStatus> statuses);

    /**
     * 查询日期区间内的预约（统计趋势 / 时段热度）。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 预约列表
     */
    List<Reservation> findByReserveDateBetween(LocalDate from, LocalDate to);

    /**
     * 定时任务扫描：某状态下日期区间内的预约。
     *
     * @param status 状态
     * @param from   起始日期（含）
     * @param to     结束日期（含）
     * @return 预约列表
     */
    @Query("select r from Reservation r where r.status = :status and r.reserveDate between :from and :to")
    List<Reservation> findByStatusAndDateRange(@Param("status") ReservationStatus status,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /**
     * ★ 用户时段重叠校验：同一用户在同日存在区间相交的有效预约。
     *
     * @param userId   用户 ID
     * @param statuses 有效状态集合
     * @param date     日期
     * @param endSlot   待预约结束时段（开区间）
     * @param startSlot 待预约起始时段
     * @return 重叠的预约列表
     */
    @Query("select r from Reservation r "
            + "where r.userId = :userId and r.status in :statuses and r.reserveDate = :date "
            + "and r.startSlot < :endSlot and r.endSlot > :startSlot")
    List<Reservation> findOverlapping(@Param("userId") Long userId,
                                      @Param("statuses") Collection<ReservationStatus> statuses,
                                      @Param("date") LocalDate date,
                                      @Param("endSlot") int endSlot,
                                      @Param("startSlot") int startSlot);

    /**
     * 统计某座位从今天起的有效（未来）预约数 —— 删除座位前置校验。
     *
     * @param seatId   座位 ID
     * @param date     今天
     * @param statuses 有效状态集合
     * @return 预约数
     */
    @Query("select count(r) from Reservation r "
            + "where r.seatId = :seatId and r.reserveDate >= :date and r.status in :statuses")
    long countActiveFutureOfSeat(@Param("seatId") Long seatId,
                                 @Param("date") LocalDate date,
                                 @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 统计某楼层从今天起的有效（未来）预约数 —— 删除楼层前置校验。
     *
     * @param floorId  楼层 ID
     * @param date     今天
     * @param statuses 有效状态集合
     * @return 预约数
     */
    @Query("select count(r) from Reservation r "
            + "where r.floorId = :floorId and r.reserveDate >= :date and r.status in :statuses")
    long countActiveFutureOfFloor(@Param("floorId") Long floorId,
                                  @Param("date") LocalDate date,
                                  @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 统计某分区（按座位归属）从今天起的有效预约数 —— 删除分区前置校验。
     *
     * @param zoneId   分区 ID
     * @param date     今天
     * @param statuses 有效状态集合
     * @return 预约数
     */
    @Query("select count(r) from Reservation r "
            + "where r.seatId in (select s.id from Seat s where s.zoneId = :zoneId) "
            + "and r.reserveDate >= :date and r.status in :statuses")
    long countActiveFutureOfZone(@Param("zoneId") Long zoneId,
                                 @Param("date") LocalDate date,
                                 @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 某人在某分区某日已占用的时段格数之和（单日累计上限判定用）。
     *
     * <p>返回的是「格数」不是分钟，调用方必须乘 {@code BizConstants.SLOT_MINUTES} 换算，
     * 不要在这里顺手乘 —— 保持 Repository 只做取数、单位换算归 BizConstants 单点。
     * 同一用户在同一分区同一日不会有时段重叠（创建时 4092 已拦），直接相加即累计值。
     *
     * @param userId   用户
     * @param date     日期
     * @param zoneId   分区
     * @param statuses 有效占用状态集合
     * @return 已占格数，无预约时为 0
     */
    @Query("select coalesce(sum(r.endSlot - r.startSlot), 0) from Reservation r "
            + "where r.userId = :userId and r.reserveDate = :date and r.status in :statuses "
            + "and r.seatId in (select s.id from Seat s where s.zoneId = :zoneId)")
    int sumSlotsOfUserZone(@Param("userId") Long userId,
                           @Param("date") LocalDate date,
                           @Param("zoneId") Long zoneId,
                           @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 某人某日在**全馆**已占用的时段格数之和（PRD §12-3 的单日累计上限判定用）。
     *
     * <p>与 {@link #sumSlotsOfUserZone} 是两道独立检查：分区口径管"本区坐了多久"，
     * 这一条管"这个人今天一共坐了多久"。只留前者的话，换区预约可以无限叠加，
     * 而"一天 6 小时"写在规则中心却没有任何消费路径。
     *
     * @param userId   用户
     * @param date     日期
     * @param statuses 有效占用状态集合
     * @return 已占格数，无预约时为 0
     */
    @Query("select coalesce(sum(r.endSlot - r.startSlot), 0) from Reservation r "
            + "where r.userId = :userId and r.reserveDate = :date and r.status in :statuses")
    int sumSlotsOfUser(@Param("userId") Long userId,
                       @Param("date") LocalDate date,
                       @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 一次组队下单产生的全部预约（签到与缺席处理按这支队伍整体操作）。
     *
     * @param groupId 队伍 ID
     * @return 预约列表
     */
    List<Reservation> findByGroupId(Long groupId);

    /**
     * 查询某楼层的全部预约（删除楼层时级联清理）。
     *
     * @param floorId 楼层 ID
     * @return 预约列表
     */
    List<Reservation> findByFloorId(Long floorId);

    /**
     * 查询某座位的全部预约（删除座位时级联清理）。
     *
     * @param seatId 座位 ID
     * @return 预约列表
     */
    List<Reservation> findBySeatId(Long seatId);
}

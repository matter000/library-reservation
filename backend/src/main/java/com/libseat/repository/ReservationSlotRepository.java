package com.libseat.repository;

import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 占用明细数据访问层（并发防重核心表）。
 */
@Repository
public interface ReservationSlotRepository extends JpaRepository<ReservationSlot, Long> {

    /**
     * ★ 占用派生唯一入口：一次查询拿到「某日期某时段」全部有效占用的 座位ID → 预约状态。
     *
     * <p>条件：{@code reserve_date=? AND slot_index=? AND status IN (CONFIRMED, CHECKED_IN)}。
     *
     * @param date     日期
     * @param slot     时段序号
     * @param statuses 有效占用状态集合
     * @return 每行 [seatId(Long), ReservationStatus]
     */
    @Query("select s.seatId, r.status "
            + "from ReservationSlot s join s.reservation r "
            + "where s.reserveDate = :date and s.slotIndex = :slot and r.status in :statuses")
    List<Object[]> findOccupiedByDateAndSlot(@Param("date") LocalDate date,
                                             @Param("slot") int slot,
                                             @Param("statuses") Collection<ReservationStatus> statuses);

    /**
     * 查询某座位某日期的全部占用行。
     *
     * @param seatId 座位 ID
     * @param date   日期
     * @return 占用行列表
     */
    List<ReservationSlot> findBySeatIdAndReserveDate(Long seatId, LocalDate date);

    /**
     * 物理删除某预约的全部占用行（取消 / NO_SHOW / COMPLETED / 管理员释放）。
     *
     * @param reservationId 预约 ID
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query(value = "delete from t_reservation_slot where reservation_id = :reservationId", nativeQuery = true)
    int deleteByReservationId(@Param("reservationId") Long reservationId);

    /**
     * 物理删除某座位的全部占用行（删除座位 / 楼层时兜底）。
     *
     * @param seatId 座位 ID
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query(value = "delete from t_reservation_slot where seat_id = :seatId", nativeQuery = true)
    int deleteBySeatId(@Param("seatId") Long seatId);
}

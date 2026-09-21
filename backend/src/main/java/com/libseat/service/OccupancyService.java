package com.libseat.service;

import com.libseat.entity.Seat;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ 占用派生核心（全局唯一入口）。
 *
 * <p>座位占用状态不落库，由 {@code t_reservation_slot} 按「日期 + 时段」实时派生。
 * 平面图 / 楼层空闲数 / 使用情况 / 统计利用率全部复用本类，杜绝派生逻辑双写。
 */
@Service
@RequiredArgsConstructor
public class OccupancyService {

    /** 会占用座位的状态集合（只有 CONFIRMED 与 CHECKED_IN）。 */
    public static final List<ReservationStatus> OCCUPYING_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final ReservationSlotRepository reservationSlotRepository;

    /**
     * 取得「某日期某时段」的全部有效占用。
     *
     * <p>只查一次库，返回 {@code seatId -> ReservationStatus}。
     *
     * @param date 日期
     * @param slot 时段序号
     * @return 座位 ID → 预约状态
     */
    @Transactional(readOnly = true)
    public Map<Long, ReservationStatus> getOccupiedMap(LocalDate date, int slot) {
        Map<Long, ReservationStatus> map = new HashMap<>();
        if (date == null) {
            return map;
        }
        List<Object[]> rows = reservationSlotRepository.findOccupiedByDateAndSlot(date, slot, OCCUPYING_STATUSES);
        for (Object[] row : rows) {
            Object seatId = row[0];
            Object status = row[1];
            if (seatId == null || status == null) {
                continue;
            }
            long key = ((Number) seatId).longValue();
            map.put(key, (ReservationStatus) status);
        }
        return map;
    }

    /**
     * 派生座位的展示态（DESIGN §6.1）。
     *
     * <ol>
     *   <li>楼层 CLOSED 或 seat.status ≠ AVAILABLE → UNAVAILABLE；</li>
     *   <li>无有效预约 → FREE；</li>
     *   <li>CONFIRMED → RESERVED；</li>
     *   <li>CHECKED_IN → IN_USE。</li>
     * </ol>
     *
     * @param seat        座位
     * @param floorClosed 楼层是否已关闭
     * @param occupiedMap 占用 Map
     * @return 展示态
     */
    public SeatDisplayStatus derive(Seat seat, boolean floorClosed, Map<Long, ReservationStatus> occupiedMap) {
        if (floorClosed || seat.getStatus() != com.libseat.enums.SeatStatus.AVAILABLE) {
            return SeatDisplayStatus.UNAVAILABLE;
        }
        ReservationStatus status = occupiedMap.get(seat.getId());
        if (status == null) {
            return SeatDisplayStatus.FREE;
        }
        if (status == ReservationStatus.CHECKED_IN) {
            return SeatDisplayStatus.IN_USE;
        }
        if (status == ReservationStatus.CONFIRMED) {
            return SeatDisplayStatus.RESERVED;
        }
        return SeatDisplayStatus.FREE;
    }
}

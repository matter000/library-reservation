package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.dto.StatsDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员·数据统计：概览 / 趋势 / 时段热度 / 楼层利用率 / 状态分布。
 *
 * <p>全部指标分母为 0 时返回 0，不除零、不 NPE（清空预约表后仍返回 200）。
 */
@Service
@RequiredArgsConstructor
public class AdminStatisticsService {

    private static final int DEFAULT_DAYS = 7;

    private final ReservationRepository reservationRepository;

    private final SeatRepository seatRepository;

    private final FloorRepository floorRepository;

    private final OccupancyService occupancyService;

    /**
     * 6 大指标概览。
     *
     * @return 概览 VO
     */
    @Transactional(readOnly = true)
    public StatsDTO.OverviewVO overview() {
        LocalDate today = LocalDate.now();
        long todayReservations = reservationRepository.countByReserveDate(today);
        long currentOnSite = reservationRepository.countByStatus(ReservationStatus.CHECKED_IN);
        long todayCheckedIn = reservationRepository.countByReserveDateAndStatus(today, ReservationStatus.CHECKED_IN);
        long todayCompleted = reservationRepository.countByReserveDateAndStatus(today, ReservationStatus.COMPLETED);
        long todayNoShow = reservationRepository.countByReserveDateAndStatus(today, ReservationStatus.NO_SHOW);
        long totalSeats = seatRepository.count();

        long denominator = todayCheckedIn + todayCompleted + todayNoShow;
        double checkInRate = BizConstants.rate(todayCheckedIn + todayCompleted, denominator);
        long currentUsageRateNumerator = currentOccupiedCount();
        long availableSeats = countAvailableSeats();

        return StatsDTO.OverviewVO.builder()
                .todayReservations(todayReservations)
                .currentOnSite(currentOnSite)
                .checkInRate(checkInRate)
                .todayNoShow(todayNoShow)
                .totalSeats(totalSeats)
                .currentUsageRate(BizConstants.rate(currentUsageRateNumerator, availableSeats))
                .build();
    }

    /**
     * 近 N 天预约量趋势（补齐缺失日期为 0）。
     *
     * @param days 天数，默认 7
     * @return 趋势列表
     */
    @Transactional(readOnly = true)
    public List<StatsDTO.TrendVO> trend(Integer days) {
        int safeDays = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(safeDays - 1L);
        Map<LocalDate, Long> counter = new HashMap<>();
        for (Reservation reservation : reservationRepository.findByReserveDateBetween(from, today)) {
            counter.merge(reservation.getReserveDate(), 1L, Long::sum);
        }
        List<StatsDTO.TrendVO> list = new ArrayList<>(safeDays);
        for (int i = safeDays - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            list.add(StatsDTO.TrendVO.builder()
                    .date(BizConstants.formatDate(date))
                    .count(counter.getOrDefault(date, 0L))
                    .build());
        }
        return list;
    }

    /**
     * 时段热度（固定 14 项 8~21，把 [startSlot, endSlot) 展开累加）。
     *
     * @param days 统计窗口天数，默认 7
     * @return 时段热度列表
     */
    @Transactional(readOnly = true)
    public List<StatsDTO.SlotHeatVO> slotHeat(Integer days) {
        int safeDays = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(safeDays - 1L);
        Map<Integer, Long> counter = new HashMap<>();
        for (Reservation reservation : reservationRepository.findByReserveDateBetween(from, today)) {
            for (int slot = reservation.getStartSlot(); slot < reservation.getEndSlot(); slot++) {
                if (slot < BizConstants.SLOT_INDEX_MIN || slot > BizConstants.SLOT_INDEX_MAX) {
                    continue;
                }
                counter.merge(slot, 1L, Long::sum);
            }
        }
        List<StatsDTO.SlotHeatVO> list = new ArrayList<>(BizConstants.SLOT_COUNT);
        for (int slot = BizConstants.SLOT_INDEX_MIN; slot <= BizConstants.SLOT_INDEX_MAX; slot++) {
            list.add(StatsDTO.SlotHeatVO.builder()
                    .slotIndex(slot)
                    .label(BizConstants.slotLabel(slot))
                    .count(counter.getOrDefault(slot, 0L))
                    .build());
        }
        return list;
    }

    /**
     * 各楼层利用率。
     *
     * @param date 日期，默认今天
     * @param slot 时段序号，默认当前时段
     * @return 楼层利用率列表
     */
    @Transactional(readOnly = true)
    public List<StatsDTO.FloorUsageVO> floorUsage(LocalDate date, Integer slot) {
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? BizConstants.clampToSlotRange(java.time.LocalDateTime.now().getHour())
                : BizConstants.clampToSlotRange(slot);
        Map<Long, ReservationStatus> occupiedMap = occupancyService.getOccupiedMap(targetDate, targetSlot);

        List<Floor> floors = floorRepository.findAllByOrderBySortOrderAscIdAsc();
        List<StatsDTO.FloorUsageVO> list = new ArrayList<>(floors.size());
        for (Floor floor : floors) {
            boolean floorClosed = floor.getStatus() == com.libseat.enums.FloorStatus.CLOSED;
            List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floor.getId());
            long occupied = 0;
            long usable = 0;
            for (Seat seat : seats) {
                SeatDisplayStatus display = occupancyService.derive(seat, floorClosed, occupiedMap);
                if (display == SeatDisplayStatus.RESERVED || display == SeatDisplayStatus.IN_USE) {
                    occupied++;
                }
                if (display != SeatDisplayStatus.UNAVAILABLE) {
                    usable++;
                }
            }
            list.add(StatsDTO.FloorUsageVO.builder()
                    .floorId(floor.getId())
                    .name(floor.getName())
                    .code(floor.getCode())
                    .totalSeats((long) seats.size())
                    .occupied(occupied)
                    .usageRate(BizConstants.rate(occupied, usable))
                    .build());
        }
        return list;
    }

    /**
     * 预约状态分布（6 种状态全部返回，无数据为 0）。
     *
     * @param days 统计窗口天数，默认 7
     * @return 状态分布列表
     */
    @Transactional(readOnly = true)
    public List<StatsDTO.StatusDistVO> statusDistribution(Integer days) {
        int safeDays = normalizeDays(days);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(safeDays - 1L);
        Map<ReservationStatus, Long> counter = new EnumMap<>(ReservationStatus.class);
        for (ReservationStatus status : ReservationStatus.values()) {
            counter.put(status, 0L);
        }
        for (Reservation reservation : reservationRepository.findByReserveDateBetween(from, today)) {
            counter.merge(reservation.getStatus(), 1L, Long::sum);
        }
        List<StatsDTO.StatusDistVO> list = new ArrayList<>(ReservationStatus.values().length);
        for (ReservationStatus status : ReservationStatus.values()) {
            list.add(StatsDTO.StatusDistVO.builder()
                    .status(status.name())
                    .statusText(status.getText())
                    .count(counter.getOrDefault(status, 0L))
                    .build());
        }
        return list;
    }

    private long currentOccupiedCount() {
        LocalDate today = LocalDate.now();
        int slot = BizConstants.clampToSlotRange(java.time.LocalDateTime.now().getHour());
        Map<Long, ReservationStatus> occupiedMap = occupancyService.getOccupiedMap(today, slot);
        return occupiedMap.size();
    }

    private long countAvailableSeats() {
        return seatRepository.countByStatus(SeatStatus.AVAILABLE);
    }

    private int normalizeDays(Integer days) {
        if (days == null || days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, 365);
    }
}

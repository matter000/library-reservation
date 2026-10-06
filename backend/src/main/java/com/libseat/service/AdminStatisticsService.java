package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.dto.StatsDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.entity.Zone;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.ZoneRepository;
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

    /** 未分区座位在下钻明细里的桶名（zone_id 为 NULL，见 {@code ZoneService.delete} 的退回语义）。 */
    private static final String UNASSIGNED_ZONE_NAME = "未分区";

    private final ReservationRepository reservationRepository;

    private final SeatRepository seatRepository;

    private final FloorRepository floorRepository;

    private final OccupancyService occupancyService;

    private final ZoneRepository zoneRepository;

    private final OpenRuleService openRuleService;

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
     * 时段热度（固定 {@code SLOT_COUNT} 项，即全局开放窗口的每个 30 分钟格，把 [startSlot, endSlot) 展开累加）。
     *
     * <p>窗口外的格恒为 0：闭馆档案更短时末尾会出现零值柱，这是「全量格子」视图的取舍，
     * 换取前端坐标轴在不同日期下宽度一致。
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
                if (slot < BizConstants.DEFAULT_WINDOW_START || slot > BizConstants.DEFAULT_WINDOW_MAX) {
                    continue;
                }
                counter.merge(slot, 1L, Long::sum);
            }
        }
        List<StatsDTO.SlotHeatVO> list = new ArrayList<>(BizConstants.SLOT_COUNT);
        for (int slot = BizConstants.DEFAULT_WINDOW_START; slot <= BizConstants.DEFAULT_WINDOW_MAX; slot++) {
            list.add(StatsDTO.SlotHeatVO.builder()
                    .slotIndex(slot)
                    .label(BizConstants.slotLabel(slot))
                    .count(counter.getOrDefault(slot, 0L))
                    .build());
        }
        return list;
    }

    /**
     * 各楼层利用率（含分区下钻）。
     *
     * <p>楼层合计与分区明细在同一次座位遍历里累加，因此「Σ 分区 occupied == 楼层 occupied」
     * 与「Σ 分区 totalSeats == 楼层 totalSeats」是构造上成立的，不靠两处各算一遍再对齐。
     *
     * @param date 日期，默认今天
     * @param slot 时段序号，默认当前时段
     * @return 楼层利用率列表
     */
    @Transactional(readOnly = true)
    public List<StatsDTO.FloorUsageVO> floorUsage(LocalDate date, Integer slot) {
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? currentSlot() : BizConstants.clampToSlotRange(slot);
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap = occupancyService.getOccupancy(targetDate, targetSlot);

        List<Floor> floors = floorRepository.findAllByOrderBySortOrderAscIdAsc();
        List<StatsDTO.FloorUsageVO> list = new ArrayList<>(floors.size());
        for (Floor floor : floors) {
            // 与楼层卡 / 平面图 / 分区卡同一派生入口：闭馆日也必须整体置灰
            boolean floorClosed = floor.getStatus() == com.libseat.enums.FloorStatus.CLOSED
                    || openRuleService.isClosed(targetDate);
            List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floor.getId());
            // key = zoneId（null 键即「未分区」桶，见 ZoneService.delete 会把座位退回 null）
            Map<Long, long[]> byZone = new HashMap<>();
            long occupied = 0;
            long usable = 0;
            for (Seat seat : seats) {
                SeatDisplayStatus display = occupancyService.derive(seat, floorClosed, occupiedMap, targetSlot);
                boolean occupiedSeat = display.occupying();
                boolean usableSeat = display.usable();
                if (occupiedSeat) {
                    occupied++;
                }
                if (usableSeat) {
                    usable++;
                }
                long[] agg = byZone.computeIfAbsent(seat.getZoneId(), key -> new long[3]);
                agg[0] += occupiedSeat ? 1 : 0;
                agg[1] += usableSeat ? 1 : 0;
                agg[2]++;
            }
            list.add(StatsDTO.FloorUsageVO.builder()
                    .floorId(floor.getId())
                    .name(floor.getName())
                    .code(floor.getCode())
                    .totalSeats((long) seats.size())
                    .occupied(occupied)
                    .usageRate(BizConstants.rate(occupied, usable))
                    .zones(zoneUsage(floor.getId(), byZone))
                    .build());
        }
        return list;
    }

    /**
     * 由楼层遍历得到的按区累加值构造下钻明细。
     *
     * <p>楼层下的空分区仍会返回（占位为 0，便于管理员看出「这个区还没座位」），
     * 未分区座位单独成桶并排在最后，保证明细与楼层合计对得上。
     */
    private List<StatsDTO.ZoneUsageVO> zoneUsage(Long floorId, Map<Long, long[]> byZone) {
        List<Zone> zones = zoneRepository.findByFloorIdOrderBySortOrderAscIdAsc(floorId);
        List<StatsDTO.ZoneUsageVO> list = new ArrayList<>(zones.size() + 1);
        for (Zone zone : zones) {
            long[] agg = byZone.remove(zone.getId());
            list.add(toZoneVO(zone.getId(), zone.getName(), zone.getZoneType().getText(), agg));
        }
        long[] unassigned = byZone.remove(null);
        if (unassigned != null) {
            list.add(toZoneVO(null, UNASSIGNED_ZONE_NAME, "待归类", unassigned));
        }
        return list;
    }

    private StatsDTO.ZoneUsageVO toZoneVO(Long zoneId, String name, String typeText, long[] agg) {
        long occupied = agg == null ? 0 : agg[0];
        long usable = agg == null ? 0 : agg[1];
        long total = agg == null ? 0 : agg[2];
        return StatsDTO.ZoneUsageVO.builder()
                .zoneId(zoneId)
                .name(name)
                .zoneTypeText(typeText)
                .totalSeats(total)
                .occupied(occupied)
                .usageRate(BizConstants.rate(occupied, usable))
                .build();
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
        int slot = currentSlot();
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap = occupancyService.getOccupancy(today, slot);
        return occupiedMap.size();
    }

    /** 当前所处的 30 分钟时段序号。 */
    private int currentSlot() {
        return BizConstants.clampToSlotRange(
                BizConstants.slotOf(java.time.LocalTime.now()));
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

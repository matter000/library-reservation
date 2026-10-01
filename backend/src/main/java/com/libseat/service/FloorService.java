package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.FloorDTO;
import com.libseat.dto.SeatDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.PeakWindow;
import com.libseat.entity.Seat;
import com.libseat.entity.Zone;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.enums.UserRole;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户端楼层服务：楼层列表（含空闲数）、楼层详情、座位平面图、时段选项。
 */
@Service
@RequiredArgsConstructor
public class FloorService {

    private final FloorRepository floorRepository;

    private final SeatRepository seatRepository;

    private final ZoneRepository zoneRepository;

    private final OccupancyService occupancyService;

    private final OpenRuleService openRuleService;

    /**
     * 楼层列表（含各楼层空闲数与占用率）。
     *
     * @param date 日期，为空取今天
     * @param slot 时段序号，为空取当前时段
     * @return 楼层 VO 列表
     */
    @Transactional(readOnly = true)
    public List<FloorDTO.FloorVO> listFloors(LocalDate date, Integer slot) {
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? currentSlot() : BizConstants.clampToSlotRange(slot);
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap = occupancyService.getOccupancy(targetDate, targetSlot);

        List<Floor> floors = floorRepository.findAllByOrderBySortOrderAscIdAsc();
        List<FloorDTO.FloorVO> result = new ArrayList<>(floors.size());
        for (Floor floor : floors) {
            List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floor.getId());
            result.add(buildFloorVO(floor, seats, occupiedMap, targetDate, targetSlot));
        }
        return result;
    }

    /**
     * 楼层详情。
     *
     * @param floorId 楼层 ID
     * @param date    日期，为空取今天
     * @param slot    时段序号，为空取当前时段
     * @return 楼层 VO
     */
    @Transactional(readOnly = true)
    public FloorDTO.FloorVO getFloor(Long floorId, LocalDate date, Integer slot) {
        Floor floor = requireFloor(floorId);
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? currentSlot() : BizConstants.clampToSlotRange(slot);
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap = occupancyService.getOccupancy(targetDate, targetSlot);
        List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floorId);
        return buildFloorVO(floor, seats, occupiedMap, targetDate, targetSlot);
    }

    /**
     * ★ 座位平面图（响应中不包含任何占用人身份）。
     *
     * @param floorId 楼层 ID
     * @param date    日期，为空取今天
     * @param slot    时段序号，为空取当前时段
     * @return 平面图 VO
     */
    @Transactional(readOnly = true)
    public SeatDTO.SeatMapVO getSeatMap(Long floorId, LocalDate date, Integer slot, Long zoneId, UserRole role) {
        Floor floor = requireFloor(floorId);
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? currentSlot() : BizConstants.clampToSlotRange(slot);
        // 闭馆（日型整体闭馆或日历临时闭馆）与楼层关闭同样处理：整层座位转为不可用
        boolean floorClosed = floor.getStatus() == FloorStatus.CLOSED || openRuleService.isClosed(targetDate);

        List<Seat> seats = (zoneId == null)
                ? seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floorId)
                : seatRepository.findByZoneIdOrderByRowNoAscColNoAsc(zoneId);
        Zone zone = null;
        if (zoneId != null) {
            zone = zoneRepository.findById(zoneId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分区不存在"));
            if (!zone.getFloorId().equals(floorId)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "分区不属于该楼层");
            }
            // 不可见分区整体当作关闭处理：不返回座位，避免用平面图反推专区分布
            if (!ZoneService.containsRole(zone.getVisibleRoles(), role)) {
                floorClosed = true;
            }
        }
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap =
                occupancyService.getOccupancy(targetDate, targetSlot);

        int maxRow = 0;
        int maxCol = 0;
        long free = 0;
        long reserved = 0;
        long inUse = 0;
        long onLeave = 0;
        long unavailable = 0;

        List<SeatDTO.SeatItemVO> items = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            SeatDisplayStatus display = occupancyService.derive(seat, floorClosed, occupiedMap, targetSlot);
            switch (display) {
                case FREE -> free++;
                case RESERVED -> reserved++;
                case IN_USE -> inUse++;
                case ON_LEAVE -> onLeave++;
                case UNAVAILABLE -> unavailable++;
            }
            OccupancyService.SeatOccupancy occupancy = occupiedMap.get(seat.getId());
            maxRow = Math.max(maxRow, seat.getRowNo() == null ? 0 : seat.getRowNo());
            maxCol = Math.max(maxCol, seat.getColNo() == null ? 0 : seat.getColNo());
            items.add(SeatDTO.SeatItemVO.builder()
                    .seatId(seat.getId())
                    .seatNo(seat.getSeatNo())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .zone(seat.getZone())
                    .zoneId(seat.getZoneId())
                    .tableId(seat.getTableId())
                    .baseStatus(seat.getStatus() == null ? SeatStatus.AVAILABLE.name() : seat.getStatus().name())
                    .displayStatus(display.name())
                    // 「暂离中 · 预计 14:35 返回」的文案由数据支撑；只给时刻，不含任何占用人身份
                    .expectedReturnAt(display == SeatDisplayStatus.ON_LEAVE && occupancy != null
                            ? BizConstants.formatTime(occupancy.leaveUntil()) : null)
                    .build());
        }

        long total = seats.size();
        long occupied = reserved + inUse + onLeave;
        long usable = total - unavailable;
        SeatDTO.SeatMapStatsVO stats = SeatDTO.SeatMapStatsVO.builder()
                .total(total)
                .free(free)
                .reserved(reserved)
                .inUse(inUse)
                .onLeave(onLeave)
                .unavailable(unavailable)
                .usageRate(BizConstants.rate(occupied, usable))
                .build();

        return SeatDTO.SeatMapVO.builder()
                .floorId(floor.getId())
                .floorName(floor.getName())
                .floorCode(floor.getCode())
                .floorStatus(floor.getStatus() == null ? FloorStatus.OPEN.name() : floor.getStatus().name())
                .date(BizConstants.formatDate(targetDate))
                .slotIndex(targetSlot)
                .slotLabel(BizConstants.slotLabel(targetSlot))
                .zoneId(zone == null ? null : zone.getId())
                .zoneName(zone == null ? null : zone.getName())
                .zoneRules(zone == null ? null : buildZoneRules(zone, targetDate, targetSlot, role))
                .maxRow(maxRow)
                .maxCol(maxCol)
                .seats(items)
                .stats(stats)
                .build();
    }

    /**
     * 组装分区有效规则（服务端单点判定，前端不再重复实现）。
     */
    private SeatDTO.ZoneRuleVO buildZoneRules(Zone zone, LocalDate date, int slot, UserRole role) {
        boolean eligible = ZoneService.containsRole(zone.getEligibleRoles(), role);
        OpenRuleService.DayRule day = openRuleService.resolve(date);
        PeakWindow peak = day.peakOf(slot);
        Integer maxMin = zone.getMaxDurationMin();
        if (peak != null && peak.getMaxDurationMin() != null
                && (maxMin == null || peak.getMaxDurationMin() < maxMin)) {
            maxMin = peak.getMaxDurationMin();
        }
        return SeatDTO.ZoneRuleVO.builder()
                .zoneId(zone.getId())
                .zoneName(zone.getName())
                .zoneTypeText(zone.getZoneType().getText())
                .eligible(eligible)
                .ineligibleReason(eligible ? null
                        : zone.getName() + " 仅对 "
                        + zone.getEligibleRoles().replace(",", "、") + " 开放，当前角色不可预约")
                .maxDurationMin(maxMin)
                .advanceDays(zone.getAdvanceDays())
                .leaveAllowed(zone.getAllowLeave())
                .leaveMaxMin(zone.getLeaveMaxMin())
                .groupRequired(zone.getGroupRequired())
                .peakFlag(peak != null)
                .build();
    }

    /**
     * 时段选项（按当日开放窗口返回 30 分钟格）。
     *
     * @param date 日期，为空取今天
     * @return 时段选项列表
     */
    @Transactional(readOnly = true)
    public List<FloorDTO.SlotOptionVO> getSlotOptions(LocalDate date) {
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        LocalDateTime now = LocalDateTime.now();
        boolean isToday = targetDate.equals(LocalDate.now());
        int currentSlot = currentSlot();
        OpenRuleService.DayRule day = openRuleService.resolve(targetDate);

        // 窗口来自当日开放规则而非全局常量：周末 / 节假日 / 临时闭馆都会反映到格数上
        // 闭馆日必须返回空列表：不能退化成「从 0 到 0」的一格假选项
        if (day.isClosed()) {
            return List.of();
        }
        int from = day.getOpenStart();
        int to = day.getOpenEnd() - 1;

        List<FloorDTO.SlotOptionVO> options = new ArrayList<>(Math.max(0, to - from + 1));
        for (int i = from; i <= to; i++) {
            LocalDateTime start = BizConstants.slotStart(targetDate, i);
            boolean expired = isToday && !start.isAfter(now);
            options.add(FloorDTO.SlotOptionVO.builder()
                    .slotIndex(i)
                    .label(BizConstants.slotLabel(i))
                    .start(BizConstants.slotText(i))
                    .end(BizConstants.slotText(i + 1))
                    .bookable(!expired)
                    .reason(expired ? "该时段已过" : null)
                    .peak(day.peakOf(i) != null)
                    .isCurrent(isToday && i == currentSlot)
                    .build());
        }
        return options;
    }

    /**
     * 按 ID 取楼层，不存在抛 4040。
     *
     * @param floorId 楼层 ID
     * @return 楼层
     */
    @Transactional(readOnly = true)
    public Floor requireFloor(Long floorId) {
        if (floorId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "楼层 ID 不能为空");
        }
        return floorRepository.findById(floorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "楼层不存在"));
    }

    /**
     * 当前所处的 30 分钟时段序号（收敛到合法区间）。
     *
     * @return 时段序号
     */
    public int currentSlot() {
        return BizConstants.clampToSlotRange(BizConstants.slotOf(LocalTime.now()));
    }

    private FloorDTO.FloorVO buildFloorVO(Floor floor, List<Seat> seats,
                                          Map<Long, OccupancyService.SeatOccupancy> occupiedMap,
                                          LocalDate date, int slot) {
        boolean floorClosed = floor.getStatus() == FloorStatus.CLOSED || openRuleService.isClosed(date);
        long total = seats.size();
        long occupied = 0;
        long unavailable = 0;
        for (Seat seat : seats) {
            SeatDisplayStatus display = occupancyService.derive(seat, floorClosed, occupiedMap, slot);
            if (display == SeatDisplayStatus.UNAVAILABLE) {
                unavailable++;
            } else if (display.occupying()) {
                occupied++;
            }
        }
        long usable = total - unavailable;
        long free = usable - occupied;
        return FloorDTO.FloorVO.builder()
                .floorId(floor.getId())
                .name(floor.getName())
                .code(floor.getCode())
                .description(floor.getDescription())
                .sortOrder(floor.getSortOrder())
                .status(floor.getStatus() == null ? FloorStatus.OPEN.name() : floor.getStatus().name())
                .statusText(floor.getStatus() == null ? FloorStatus.OPEN.getText() : floor.getStatus().getText())
                .totalSeats(total)
                .freeSeats(free)
                .occupiedSeats(occupied)
                .unavailableSeats(unavailable)
                .usageRate(BizConstants.rate(occupied, usable))
                .seatCount(total)
                .createTime(BizConstants.formatDateTime(floor.getCreateTime()))
                .build();
    }
}

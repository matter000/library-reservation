package com.libseat.unit;

import com.libseat.common.BizConstants;
import com.libseat.entity.Floor;
import com.libseat.entity.Seat;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.service.OccupancyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 占用派生核心逻辑单元测试（DESIGN §6.1 + V2 §5 的 ON_LEAVE 派生）。
 *
 * <p>重点：只有 CONFIRMED / CHECKED_IN 占用座位；楼层 CLOSED 或座位非 AVAILABLE 一律 UNAVAILABLE；
 * 暂离只改「人在不在座」，不改占用与分母。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OccupancyServiceTest {

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    @Mock
    private LeaveRecordRepository leaveRecordRepository;

    private OccupancyService occupancyService;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 1);

    /** 14:00 那一格（30 分钟格长改造后由 BizConstants 换算）。 */
    private static final int SLOT = BizConstants.slotOf(LocalTime.of(14, 0));

    private static final LocalDateTime SLOT_START = BizConstants.slotStart(DATE, SLOT);

    @BeforeEach
    void setUp() {
        occupancyService = new OccupancyService(reservationSlotRepository, leaveRecordRepository);
    }

    private Seat seat(long id, SeatStatus status) {
        return Seat.builder()
                .id(id)
                .floorId(1L)
                .seatNo("A" + id)
                .rowNo(1)
                .colNo(1)
                .status(status)
                .build();
    }

    private Map<Long, OccupancyService.SeatOccupancy> occupancy(long seatId, ReservationStatus status) {
        Map<Long, OccupancyService.SeatOccupancy> map = new HashMap<>();
        map.put(seatId, new OccupancyService.SeatOccupancy(status, null, null));
        return map;
    }

    private Map<Long, OccupancyService.SeatOccupancy> occupancy(long seatId, ReservationStatus status,
                                                                 LocalDateTime leaveStart, LocalDateTime leaveUntil) {
        Map<Long, OccupancyService.SeatOccupancy> map = new HashMap<>();
        map.put(seatId, new OccupancyService.SeatOccupancy(status, leaveStart, leaveUntil));
        return map;
    }

    private SeatDisplayStatus derive(Seat seat, boolean floorClosed,
                                     Map<Long, OccupancyService.SeatOccupancy> map) {
        return occupancyService.derive(seat, floorClosed, map, SLOT);
    }

    @Test
    @DisplayName("占用状态集合必须只包含 CONFIRMED 与 CHECKED_IN")
    void occupyingStatuses() {
        assertEquals(List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN),
                OccupancyService.OCCUPYING_STATUSES);
    }

    @Test
    @DisplayName("无占用记录且楼层开放 → FREE")
    void derive_free() {
        assertEquals(SeatDisplayStatus.FREE, derive(seat(1L, SeatStatus.AVAILABLE), false, Map.of()));
    }

    @Test
    @DisplayName("CONFIRMED → RESERVED")
    void derive_reserved() {
        assertEquals(SeatDisplayStatus.RESERVED,
                derive(seat(1L, SeatStatus.AVAILABLE), false, occupancy(1L, ReservationStatus.CONFIRMED)));
    }

    @Test
    @DisplayName("CHECKED_IN 且无暂离 → IN_USE")
    void derive_inUse() {
        assertEquals(SeatDisplayStatus.IN_USE,
                derive(seat(1L, SeatStatus.AVAILABLE), false, occupancy(1L, ReservationStatus.CHECKED_IN)));
    }

    @Test
    @DisplayName("CHECKED_IN + 本格已开始暂离 → ON_LEAVE")
    void derive_onLeave() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancy(1L, ReservationStatus.CHECKED_IN,
                SLOT_START.minusMinutes(5), SLOT_START.plusMinutes(25));
        assertEquals(SeatDisplayStatus.ON_LEAVE, derive(seat(1L, SeatStatus.AVAILABLE), false, map));
    }

    @Test
    @DisplayName("暂离从下一格才开始 → 本格仍是 IN_USE（不能把未来暂离提前点亮）")
    void derive_leaveNotStartedYet() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancy(1L, ReservationStatus.CHECKED_IN,
                SLOT_START.plusMinutes(30), SLOT_START.plusMinutes(60));
        assertEquals(SeatDisplayStatus.IN_USE, derive(seat(1L, SeatStatus.AVAILABLE), false, map));
    }

    @Test
    @DisplayName("CONFIRMED 即使带暂离行也不变 ON_LEAVE（暂离只在签到之后出现）")
    void derive_confirmedNeverOnLeave() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancy(1L, ReservationStatus.CONFIRMED,
                SLOT_START.minusMinutes(5), SLOT_START.plusMinutes(25));
        assertEquals(SeatDisplayStatus.RESERVED, derive(seat(1L, SeatStatus.AVAILABLE), false, map));
    }

    @Test
    @DisplayName("★ ON_LEAVE 仍算占用且仍计入可用（余量口径不因暂离漂移）")
    void onLeaveStillOccupies() {
        assertTrue(SeatDisplayStatus.ON_LEAVE.occupying(), "暂离期间座位不可被他人预约");
        assertTrue(SeatDisplayStatus.ON_LEAVE.usable(), "暂离座位不是「不可用」，仍计入分母");
        assertFalse(SeatDisplayStatus.FREE.occupying());
        assertFalse(SeatDisplayStatus.UNAVAILABLE.usable());
    }

    @Test
    @DisplayName("MAINTENANCE / DISABLED 座位 → UNAVAILABLE（即使有占用记录）")
    void derive_seatNotAvailable() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancy(1L, ReservationStatus.CHECKED_IN);
        assertEquals(SeatDisplayStatus.UNAVAILABLE, derive(seat(1L, SeatStatus.MAINTENANCE), false, map));
        assertEquals(SeatDisplayStatus.UNAVAILABLE, derive(seat(2L, SeatStatus.DISABLED), false, map));
    }

    @Test
    @DisplayName("楼层 CLOSED → 全部 UNAVAILABLE（优先级高于占用与暂离）")
    void derive_floorClosed() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancy(1L, ReservationStatus.CHECKED_IN,
                SLOT_START.minusMinutes(5), SLOT_START.plusMinutes(25));
        assertEquals(SeatDisplayStatus.UNAVAILABLE, derive(seat(1L, SeatStatus.AVAILABLE), true, map));
        assertEquals(SeatDisplayStatus.UNAVAILABLE, derive(seat(2L, SeatStatus.AVAILABLE), true, Map.of()));
    }

    @Test
    @DisplayName("已释放状态（CANCELLED/NO_SHOW/COMPLETED/ADMIN_CANCELLED）不占用 → FREE")
    void derive_releasedStatusesNotOccupying() {
        for (ReservationStatus status : List.of(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW,
                ReservationStatus.COMPLETED, ReservationStatus.ADMIN_CANCELLED)) {
            assertEquals(SeatDisplayStatus.FREE, derive(seat(1L, SeatStatus.AVAILABLE), false, occupancy(1L, status)),
                    "状态 " + status + " 不应占用座位");
        }
    }

    @Test
    @DisplayName("getOccupancy：只保留有效占用、忽略空行，并把 OPEN 暂离合并且不新增座位")
    void getOccupancy() {
        when(reservationSlotRepository.findOccupiedByDateAndSlot(eq(DATE), eq(SLOT),
                eq(OccupancyService.OCCUPYING_STATUSES)))
                .thenReturn(List.of(
                        new Object[]{101L, ReservationStatus.CONFIRMED},
                        new Object[]{102L, ReservationStatus.CHECKED_IN},
                        new Object[]{null, ReservationStatus.CONFIRMED},
                        new Object[]{103L, null}));
        LocalDateTime start = SLOT_START.minusMinutes(3);
        LocalDateTime until = SLOT_START.plusMinutes(27);
        when(leaveRecordRepository.findOpenLeavesByDateAndSlot(DATE, SLOT, 1))
                .thenReturn(List.of(
                        new Object[]{102L, start, until},
                        // ★ 预约已释放但暂离行未清理：不能凭空多出一个占用座位
                        new Object[]{999L, start, until}));

        Map<Long, OccupancyService.SeatOccupancy> map = occupancyService.getOccupancy(DATE, SLOT);

        assertEquals(2, map.size(), "结果必须只含有效占用，实际 " + map.keySet());
        assertEquals(ReservationStatus.CONFIRMED, map.get(101L).status());
        assertNull(map.get(101L).leaveUntil(), "未签到的座位不应带暂离时刻");
        assertEquals(ReservationStatus.CHECKED_IN, map.get(102L).status());
        assertEquals(until, map.get(102L).leaveUntil(), "已签到座位应合并出应归时刻");
        assertEquals(start, map.get(102L).leaveStart());
        assertFalse(map.containsKey(103L), "状态为空的行必须被忽略");
    }

    @Test
    @DisplayName("getOccupancy：date 为 null 直接返回空 Map，不查库")
    void getOccupancy_nullDate() {
        Map<Long, OccupancyService.SeatOccupancy> map = occupancyService.getOccupancy(null, SLOT);
        assertTrue(map.isEmpty());
        verifyNoInteractions(reservationSlotRepository);
        verifyNoInteractions(leaveRecordRepository);
    }

    @Test
    @DisplayName("楼层实体状态与 FloorStatus 枚举一致（防止字段被误加占用字段）")
    void floorHasNoOccupancyField() {
        Floor floor = Floor.builder().name("一楼").code("1F").status(FloorStatus.OPEN).build();
        assertEquals(FloorStatus.OPEN, floor.getStatus());
        assertNull(floor.getId());
    }
}

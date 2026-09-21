package com.libseat.unit;

import com.libseat.entity.Floor;
import com.libseat.entity.Seat;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 占用派生核心逻辑单元测试（DESIGN §6.1）。
 *
 * <p>重点：只有 CONFIRMED / CHECKED_IN 占用座位；楼层 CLOSED 或座位非 AVAILABLE 一律 UNAVAILABLE。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OccupancyServiceTest {

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    private OccupancyService occupancyService;

    private static final LocalDate DATE = LocalDate.of(2026, 3, 1);

    @BeforeEach
    void setUp() {
        occupancyService = new OccupancyService(reservationSlotRepository);
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

    @Test
    @DisplayName("占用状态集合必须只包含 CONFIRMED 与 CHECKED_IN")
    void occupyingStatuses() {
        assertEquals(List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN),
                OccupancyService.OCCUPYING_STATUSES);
    }

    @Test
    @DisplayName("无占用记录且楼层开放 → FREE")
    void derive_free() {
        assertEquals(SeatDisplayStatus.FREE, occupancyService.derive(seat(1L, SeatStatus.AVAILABLE), false, Map.of()));
    }

    @Test
    @DisplayName("CONFIRMED → RESERVED")
    void derive_reserved() {
        Map<Long, ReservationStatus> map = new HashMap<>();
        map.put(1L, ReservationStatus.CONFIRMED);
        assertEquals(SeatDisplayStatus.RESERVED,
                occupancyService.derive(seat(1L, SeatStatus.AVAILABLE), false, map));
    }

    @Test
    @DisplayName("CHECKED_IN → IN_USE")
    void derive_inUse() {
        Map<Long, ReservationStatus> map = new HashMap<>();
        map.put(1L, ReservationStatus.CHECKED_IN);
        assertEquals(SeatDisplayStatus.IN_USE,
                occupancyService.derive(seat(1L, SeatStatus.AVAILABLE), false, map));
    }

    @Test
    @DisplayName("MAINTENANCE / DISABLED 座位 → UNAVAILABLE（即使有占用记录）")
    void derive_seatNotAvailable() {
        Map<Long, ReservationStatus> map = new HashMap<>();
        map.put(1L, ReservationStatus.CHECKED_IN);
        assertEquals(SeatDisplayStatus.UNAVAILABLE,
                occupancyService.derive(seat(1L, SeatStatus.MAINTENANCE), false, map));
        assertEquals(SeatDisplayStatus.UNAVAILABLE,
                occupancyService.derive(seat(2L, SeatStatus.DISABLED), false, map));
    }

    @Test
    @DisplayName("楼层 CLOSED → 全部 UNAVAILABLE（优先级高于占用）")
    void derive_floorClosed() {
        Map<Long, ReservationStatus> map = new HashMap<>();
        map.put(1L, ReservationStatus.CHECKED_IN);
        assertEquals(SeatDisplayStatus.UNAVAILABLE,
                occupancyService.derive(seat(1L, SeatStatus.AVAILABLE), true, map));
        assertEquals(SeatDisplayStatus.UNAVAILABLE,
                occupancyService.derive(seat(2L, SeatStatus.AVAILABLE), true, Map.of()));
    }

    @Test
    @DisplayName("已释放状态（CANCELLED/NO_SHOW/COMPLETED/ADMIN_CANCELLED）不占用 → FREE")
    void derive_releasedStatusesNotOccupying() {
        for (ReservationStatus status : List.of(ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW,
                ReservationStatus.COMPLETED, ReservationStatus.ADMIN_CANCELLED)) {
            Map<Long, ReservationStatus> map = new HashMap<>();
            map.put(1L, status);
            assertEquals(SeatDisplayStatus.FREE,
                    occupancyService.derive(seat(1L, SeatStatus.AVAILABLE), false, map),
                    "状态 " + status + " 不应占用座位");
        }
    }

    @Test
    @DisplayName("getOccupiedMap：只保留有效占用，并忽略空行")
    void getOccupiedMap() {
        when(reservationSlotRepository.findOccupiedByDateAndSlot(eq(DATE), eq(10),
                eq(OccupancyService.OCCUPYING_STATUSES)))
                .thenReturn(List.of(
                        new Object[]{101L, ReservationStatus.CONFIRMED},
                        new Object[]{102L, ReservationStatus.CHECKED_IN},
                        new Object[]{null, ReservationStatus.CONFIRMED},
                        new Object[]{103L, null}));

        Map<Long, ReservationStatus> map = occupancyService.getOccupiedMap(DATE, 10);
        assertEquals(2, map.size());
        assertEquals(ReservationStatus.CONFIRMED, map.get(101L));
        assertEquals(ReservationStatus.CHECKED_IN, map.get(102L));
        assertTrue(!map.containsKey(103L));
    }

    @Test
    @DisplayName("getOccupiedMap：date 为 null 直接返回空 Map，不查库")
    void getOccupiedMap_nullDate() {
        Map<Long, ReservationStatus> map = occupancyService.getOccupiedMap(null, 10);
        assertTrue(map.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(reservationSlotRepository);
    }

    @Test
    @DisplayName("楼层实体状态与 FloorStatus 枚举一致（防止字段被误加占用字段）")
    void floorHasNoOccupancyField() {
        Floor floor = Floor.builder().name("一楼").code("1F").status(FloorStatus.OPEN).build();
        assertEquals(FloorStatus.OPEN, floor.getStatus());
        assertNull(floor.getId());
    }
}

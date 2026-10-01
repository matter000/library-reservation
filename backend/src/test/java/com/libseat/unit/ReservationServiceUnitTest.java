package com.libseat.unit;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.ReservationDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;
import com.libseat.service.FloorService;
import com.libseat.service.OccupancyService;
import com.libseat.service.ReservationService;
import com.libseat.service.RuleConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约核心服务的单元测试（DESIGN §4.3 C1 七步校验 / §6.4 状态机）。
 *
 * <p>用 Mockito 隔离仓储层，只验证业务规则与错误码。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationServiceUnitTest {

    private static final Long USER_ID = 10L;
    private static final Long SEAT_ID = 100L;
    private static final Long FLOOR_ID = 1L;

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository reservationSlotRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FloorService floorService;
    @Mock
    private RuleConfigService ruleConfigService;
    @Mock
    private com.libseat.service.OpenRuleService openRuleService;

    private ReservationService service;

    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void setUp() {
        // 规则配置桩成默认值：本文件验证业务规则本身，配置中心的生效性由 RuleApiTest 覆盖
        when(ruleConfigService.checkInBeforeMin()).thenReturn(BizConstants.CHECK_IN_BEFORE_MIN);
        when(ruleConfigService.checkInAfterMin()).thenReturn(BizConstants.CHECK_IN_AFTER_MIN);
        when(ruleConfigService.advanceDays()).thenReturn(BizConstants.ADVANCE_DAYS);
        when(ruleConfigService.defaultDuration()).thenReturn(BizConstants.DEFAULT_DURATION);
        when(ruleConfigService.allowedDurations()).thenReturn(BizConstants.ALLOWED_DURATIONS);
        // 开放规则桩成「不闭馆的默认窗口」，闭馆与日型分支由 OpenRuleApiTest / OpenRuleServiceTest 覆盖
        when(openRuleService.resolve(any())).thenReturn(new com.libseat.service.OpenRuleService.DayRule(
                TODAY, com.libseat.enums.DayType.WORKDAY,
                BizConstants.DEFAULT_SLOT_START, BizConstants.DEFAULT_SLOT_END,
                false, List.of(), null, "工作日"));
        when(openRuleService.endSlotOf(any())).thenReturn(BizConstants.DEFAULT_SLOT_END);
        service = new ReservationService(reservationRepository, reservationSlotRepository,
                seatRepository, userRepository, floorService, ruleConfigService, openRuleService);
    }

    /** 整点 → 30 分钟格序号（格长改造后，用例仍以「小时」表达意图）。 */
    private static int slot(int hour) {
        return hour * BizConstants.SLOTS_PER_HOUR;
    }

    private Seat availableSeat() {
        return Seat.builder().id(SEAT_ID).floorId(FLOOR_ID).seatNo("A01")
                .rowNo(1).colNo(1).status(SeatStatus.AVAILABLE).build();
    }

    private Floor openFloor() {
        return Floor.builder().id(FLOOR_ID).name("一楼自习区").code("1F").status(FloorStatus.OPEN).build();
    }

    // startSlot 用包装类型：后端 DTO 字段为 Integer，需覆盖「startSlot 为空 → 4001」用例
    private ReservationDTO.ReservationCreateRequest req(LocalDate date, Integer startSlot, Integer duration) {
        ReservationDTO.ReservationCreateRequest r = new ReservationDTO.ReservationCreateRequest();
        r.setSeatId(SEAT_ID);
        r.setDate(date);
        r.setStartSlot(startSlot);
        r.setDuration(duration);
        return r;
    }

    /** 走完前 5 步（座位/楼层/日期/时段/时长）的成功前置条件。 */
    private void givenHappyPath() {
        when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.of(availableSeat()));
        when(floorService.requireFloor(FLOOR_ID)).thenReturn(openFloor());
        when(reservationRepository.findOverlapping(anyLong(), anyCollection(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    private void givenSaveOk() {
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(9001L);
            return r;
        });
        when(reservationSlotRepository.saveAllAndFlush(any())).thenReturn(Collections.emptyList());
    }

    private BusinessException expectBiz(Runnable runnable) {
        return assertThrows(BusinessException.class, runnable::run);
    }

    // ==================== C1 第 0 步：参数 ====================

    @Test
    @DisplayName("seatId 为空 → 4001")
    void create_nullSeatId() {
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
        r.setSeatId(null);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("date 为空 → 4001")
    void create_nullDate() {
        ReservationDTO.ReservationCreateRequest r = req(null, slot(10), 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("startSlot 为空 → 4001")
    void create_nullStartSlot() {
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), null, 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    // ==================== C1 第 1 步：座位 / 楼层 ====================

    @Test
    @DisplayName("座位不存在 → 4040")
    void create_seatNotFound() {
        when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.empty());
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
        assertEquals(ErrorCode.NOT_FOUND.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("座位 MAINTENANCE / DISABLED → 4093")
    void create_seatNotAvailable() {
        for (SeatStatus status : List.of(SeatStatus.MAINTENANCE, SeatStatus.DISABLED)) {
            Seat seat = availableSeat();
            seat.setStatus(status);
            when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.of(seat));
            ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
            assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                    expectBiz(() -> service.createReservation(USER_ID, r)).getCode(),
                    "座位状态 " + status + " 应拒绝预约");
        }
    }

    @Test
    @DisplayName("楼层 CLOSED → 4093")
    void create_floorClosed() {
        Floor closed = openFloor();
        closed.setStatus(FloorStatus.CLOSED);
        when(seatRepository.findById(SEAT_ID)).thenReturn(Optional.of(availableSeat()));
        when(floorService.requireFloor(FLOOR_ID)).thenReturn(closed);
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    // ==================== C1 第 2 步：日期窗口 ====================

    @Test
    @DisplayName("日期为昨天 → 4001")
    void create_yesterday() {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.minusDays(1), slot(10), 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("日期为今天+7 → 4001（超出窗口）")
    void create_tooFar() {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(7), slot(10), 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("日期为今天+6 → 允许（窗口右边界）")
    void create_lastAllowedDay() {
        givenHappyPath();
        givenSaveOk();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(6), slot(10), 2);
        assertNotNull(service.createReservation(USER_ID, r));
    }

    // ==================== C1 第 3/4 步：时段 / 时长 ====================

    @ParameterizedTest
    @ValueSource(ints = {-100, -1, 0, 5, 6, 24})
    @DisplayName("duration 非法（0/5/负数/过大）→ 4001")
    void create_invalidDuration(int duration) {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), duration);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode(),
                "duration=" + duration + " 应被拒绝");
    }

    @Test
    @DisplayName("duration 为 null → 取默认值 2 且成功")
    void create_defaultDuration() {
        givenHappyPath();
        givenSaveOk();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), null);
        ReservationDTO.ReservationCreateVO vo = service.createReservation(USER_ID, r);
        assertEquals(slot(10), vo.getStartSlot());
        assertEquals(slot(12), vo.getEndSlot());
        assertEquals("10:00-12:00", vo.getTimeRange());
    }

    @Test
    @DisplayName("起始早于开放窗口（07:00）→ 4001")
    void create_slotTooEarly() {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(7), 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("起始落在开放结束时（22:00）→ 4001")
    void create_slotTooLate() {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(22), 1);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("21:00 起约 2 小时（越过 22:00）→ 4001")
    void create_crossDay() {
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(21), 2);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("21:00 起约 1 小时 → 允许，结束边界为 22:00")
    void create_lastSlot() {
        givenHappyPath();
        givenSaveOk();
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(21), 1);
        ReservationDTO.ReservationCreateVO vo = service.createReservation(USER_ID, r);
        assertEquals(slot(22), vo.getEndSlot());
    }

    @Test
    @DisplayName("半小时格可约：21:30 起约 1 小时到 22:30 越界 → 4001；到 22:00 的 30 分钟起约合法")
    void create_halfSlotBoundary() {
        givenHappyPath();
        // 21:30 = slot 43，是合法起始格；但 1 小时会越过 22:00 边界
        ReservationDTO.ReservationCreateRequest tooLate = req(TODAY.plusDays(1), 43, 1);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(),
                expectBiz(() -> service.createReservation(USER_ID, tooLate)).getCode(),
                "★ 30 分钟格下必须按格数（slotsOf）判越界，不能把小时直接加在格号上");
        givenHappyPath();
        givenSaveOk();
        ReservationDTO.ReservationCreateRequest ok = req(TODAY.plusDays(1), 42, 1);
        assertEquals(44, service.createReservation(USER_ID, ok).getEndSlot());
    }

    @Test
    @DisplayName("今天已过去的时段 → 4001")
    void create_pastSlotToday() {
        int nowHour = LocalDateTime.now().getHour();
        if (nowHour < 9) {
            return; // 凌晨跑不了这个用例
        }
        givenHappyPath();
        ReservationDTO.ReservationCreateRequest r = req(TODAY, slot(8), 1);
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    // ==================== C1 第 6 步：用户重叠 ====================

    @Test
    @DisplayName("同一用户同时段已有有效预约 → 4092")
    void create_userOverlap() {
        givenHappyPath();
        when(reservationRepository.findOverlapping(anyLong(), anyCollection(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(Reservation.builder().id(1L).build()));
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
        assertEquals(ErrorCode.USER_SLOT_OVERLAP.getCode(),
                expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    @Test
    @DisplayName("重叠查询必须只查 CONFIRMED / CHECKED_IN")
    void create_overlapUsesOccupyingStatuses() {
        givenHappyPath();
        givenSaveOk();
        service.createReservation(USER_ID, req(TODAY.plusDays(1), slot(10), 2));
        verify(reservationRepository).findOverlapping(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(OccupancyService.OCCUPYING_STATUSES),
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(1)),
                org.mockito.ArgumentMatchers.eq(slot(12)),
                org.mockito.ArgumentMatchers.eq(slot(10)));
    }

    // ==================== C1 第 7 步：并发 ====================

    @Test
    @DisplayName("唯一索引冲突 → 4091，且不再写库")
    void create_slotConflict() {
        givenHappyPath();
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(9001L);
            return r;
        });
        when(reservationSlotRepository.saveAllAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("Unique index violation"));
        ReservationDTO.ReservationCreateRequest r = req(TODAY.plusDays(1), slot(10), 2);
        assertEquals(ErrorCode.SEAT_SLOT_CONFLICT.getCode(),
                expectBiz(() -> service.createReservation(USER_ID, r)).getCode());
    }

    // ==================== 取消状态机（§6.4） ====================

    private Reservation reservation(ReservationStatus status, LocalDate date, int startSlot) {
        return Reservation.builder()
                .id(500L).userId(USER_ID).seatId(SEAT_ID).floorId(FLOOR_ID)
                .reserveDate(date).startSlot(startSlot).endSlot(startSlot + 2)
                .status(status).build();
    }

    @Test
    @DisplayName("开始前取消 CONFIRMED → CANCELLED 且物理删除 slot")
    void cancel_beforeStart() {
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY.plusDays(3), 10);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        ReservationDTO.ReservationActionVO vo = service.cancel(USER_ID, 500L);
        assertEquals("CANCELLED", vo.getStatus());
        assertEquals(ReservationStatus.CANCELLED, r.getStatus());
        verify(reservationSlotRepository).deleteByReservationId(500L);
    }

    @Test
    @DisplayName("已开始的 CONFIRMED 取消 → 4093")
    void cancel_afterStart() {
        int nowHour = LocalDateTime.now().getHour();
        if (nowHour < 9) {
            return;
        }
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY, 8);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                expectBiz(() -> service.cancel(USER_ID, 500L)).getCode());
        verify(reservationSlotRepository, never()).deleteByReservationId(anyLong());
    }

    @Test
    @DisplayName("CHECKED_IN / COMPLETED / CANCELLED / NO_SHOW / ADMIN_CANCELLED 取消 → 4093")
    void cancel_illegalStatuses() {
        for (ReservationStatus status : List.of(ReservationStatus.CHECKED_IN, ReservationStatus.COMPLETED,
                ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW, ReservationStatus.ADMIN_CANCELLED)) {
            Reservation r = reservation(status, TODAY.plusDays(3), 10);
            when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
            assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                    expectBiz(() -> service.cancel(USER_ID, 500L)).getCode(),
                    "状态 " + status + " 不应允许取消");
        }
    }

    @Test
    @DisplayName("取消他人的预约 → 4040（防水平越权）")
    void cancel_otherUser() {
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY.plusDays(3), 10);
        r.setUserId(999L);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), expectBiz(() -> service.cancel(USER_ID, 500L)).getCode());
    }

    @Test
    @DisplayName("预约不存在 → 4040")
    void cancel_notFound() {
        when(reservationRepository.findById(500L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), expectBiz(() -> service.cancel(USER_ID, 500L)).getCode());
    }

    // ==================== 签到状态机（§6.4） ====================

    @Test
    @DisplayName("签到窗口外（明天）→ 4093")
    void checkIn_tooEarly() {
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY.plusDays(3), 10);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                expectBiz(() -> service.checkIn(USER_ID, 500L)).getCode());
    }

    @Test
    @DisplayName("签到窗口外（已开始 2 小时）→ 4093")
    void checkIn_tooLate() {
        int nowHour = LocalDateTime.now().getHour();
        if (nowHour < 10) {
            return;
        }
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY, nowHour - 2);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                expectBiz(() -> service.checkIn(USER_ID, 500L)).getCode());
    }

    @Test
    @DisplayName("非 CONFIRMED 状态签到 → 4093")
    void checkIn_illegalStatuses() {
        for (ReservationStatus status : List.of(ReservationStatus.CHECKED_IN, ReservationStatus.COMPLETED,
                ReservationStatus.CANCELLED, ReservationStatus.NO_SHOW, ReservationStatus.ADMIN_CANCELLED)) {
            Reservation r = reservation(status, TODAY, LocalDateTime.now().getHour());
            when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
            assertEquals(ErrorCode.STATUS_NOT_ALLOWED.getCode(),
                    expectBiz(() -> service.checkIn(USER_ID, 500L)).getCode(),
                    "状态 " + status + " 不应允许签到");
        }
    }

    @Test
    @DisplayName("签到他人的预约 → 4040（防水平越权）")
    void checkIn_otherUser() {
        Reservation r = reservation(ReservationStatus.CONFIRMED, TODAY, LocalDateTime.now().getHour());
        r.setUserId(999L);
        when(reservationRepository.findById(500L)).thenReturn(Optional.of(r));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), expectBiz(() -> service.checkIn(USER_ID, 500L)).getCode());
    }

    // ==================== Tab 分组 ====================

    @Test
    @DisplayName("Tab → 状态集合映射正确")
    void statusesOfTab() {
        assertEquals(List.of(ReservationStatus.CONFIRMED), service.statusesOfTab("upcoming"));
        assertEquals(List.of(ReservationStatus.CONFIRMED), service.statusesOfTab(null));
        assertEquals(List.of(ReservationStatus.CHECKED_IN), service.statusesOfTab("inProgress"));
        assertEquals(List.of(ReservationStatus.CHECKED_IN), service.statusesOfTab("in_progress"));
        assertEquals(List.of(ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW),
                service.statusesOfTab("finished"));
        assertEquals(List.of(ReservationStatus.CANCELLED, ReservationStatus.ADMIN_CANCELLED),
                service.statusesOfTab("cancelled"));
    }
}

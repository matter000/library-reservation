package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.PageResult;
import com.libseat.dto.ReservationDTO;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.entity.Seat;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ★ 预约核心服务：创建（并发防重）/ 我的预约 / 取消 / 签到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final DateTimeFormatter HM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final SeatRepository seatRepository;

    private final UserRepository userRepository;

    private final FloorService floorService;

    private final RuleConfigService ruleConfigService;

    /**
     * 提交预约（DESIGN §4.3-C1 七步校验）。
     *
     * <p>并发防重：主表 {@code saveAndFlush} 拿 id → slot 表 {@code saveAllAndFlush} 立即触发唯一索引，
     * 捕获 {@link DataIntegrityViolationException} 转换为 4091 业务异常（抛异常触发回滚）。
     *
     * @param userId  当前用户 ID（取自 UserContext，不接受前端传入）
     * @param request 预约请求
     * @return 创建结果
     */
    @Transactional
    public ReservationDTO.ReservationCreateVO createReservation(Long userId,
                                                                ReservationDTO.ReservationCreateRequest request) {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        Long seatId = request.getSeatId();
        LocalDate date = request.getDate();
        Integer startSlot = request.getStartSlot();
        Integer duration = (request.getDuration() == null) ? ruleConfigService.defaultDuration() : request.getDuration();

        // ---------- 第 0 步：参数基本校验 ----------
        if (seatId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择座位");
        }
        if (date == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择预约日期");
        }
        if (startSlot == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择起始时段");
        }

        // ---------- 第 1 步：座位可用 + 楼层开放 ----------
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "座位不存在"));
        if (seat.getStatus() != com.libseat.enums.SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该座位维护中/已停用，暂不可预约");
        }
        com.libseat.entity.Floor floor = floorService.requireFloor(seat.getFloorId());
        if (floor.getStatus() == com.libseat.enums.FloorStatus.CLOSED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该楼层暂不开放，请选择其他楼层");
        }

        // ---------- 第 2 步：日期在 [今天, 今天 + advance-days - 1] ----------
        int advanceDays = ruleConfigService.advanceDays();
        if (date.isBefore(today) || date.isAfter(today.plusDays(advanceDays - 1L))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能预约今天起 " + advanceDays + " 天内的座位");
        }

        // ---------- 第 3 步：时段在 8~22 且不跨天 ----------
        if (!BizConstants.validSlotIndex(startSlot) || startSlot + duration > BizConstants.OPEN_HOUR_END) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约时段超出开放时间（08:00-22:00）");
        }

        // ---------- 第 4 步：时长 ∈ 配置的可选值（默认 1/2/3/4 小时） ----------
        List<Integer> allowedDurations = ruleConfigService.allowedDurations();
        if (!allowedDurations.contains(duration)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "时长只能选择 "
                    + String.join("/", allowedDurations.stream().map(String::valueOf).toList()) + " 小时");
        }

        // ---------- 第 5 步：今天时起始时段未过期 ----------
        LocalDateTime start = BizConstants.slotStart(date, startSlot);
        if (!start.isAfter(now)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能预约已过去的时段");
        }

        // ---------- 第 6 步：用户无重叠有效预约 ----------
        int endSlot = startSlot + duration;
        List<Reservation> overlapping = reservationRepository.findOverlapping(
                userId, OccupancyService.OCCUPYING_STATUSES, date, endSlot, startSlot);
        if (!overlapping.isEmpty()) {
            throw new BusinessException(ErrorCode.USER_SLOT_OVERLAP, "您在该时段已有预约");
        }

        // ---------- 第 7 步：事务插入（唯一索引兜底） ----------
        Reservation reservation = Reservation.builder()
                .userId(userId)
                .seatId(seatId)
                .floorId(seat.getFloorId())
                .reserveDate(date)
                .startSlot(startSlot)
                .endSlot(endSlot)
                .status(ReservationStatus.CONFIRMED)
                .build();
        Reservation saved = reservationRepository.saveAndFlush(reservation);

        List<ReservationSlot> slots = new ArrayList<>(duration);
        for (int i = startSlot; i < endSlot; i++) {
            slots.add(ReservationSlot.builder()
                    .reservation(saved)
                    .seatId(seatId)
                    .reserveDate(date)
                    .slotIndex(i)
                    .build());
        }
        try {
            // ★ 必须主动 flush，否则冲突延迟到 commit 才暴露，无法转成友好提示
            reservationSlotRepository.saveAllAndFlush(slots);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发抢座失败：seatId={}, date={}, slots=[{},{}), userId={}",
                    seatId, date, startSlot, endSlot, userId);
            throw new BusinessException(ErrorCode.SEAT_SLOT_CONFLICT, "该座位刚被他人预约，请重新选择");
        }

        return ReservationDTO.ReservationCreateVO.builder()
                .reservationId(saved.getId())
                .seatNo(seat.getSeatNo())
                .floorName(floor.getName())
                .date(BizConstants.formatDate(date))
                .startSlot(startSlot)
                .endSlot(endSlot)
                .timeRange(BizConstants.rangeLabel(startSlot, duration))
                .status(ReservationStatus.CONFIRMED.name())
                .build();
    }

    /**
     * 我的预约（★ 强制使用 UserContext 中的 userId，防水平越权）。
     *
     * @param userId 当前用户 ID
     * @param tab    分组：upcoming / inProgress / finished / cancelled
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<ReservationDTO.ReservationVO> myReservations(Long userId, String tab, int page, int size) {
        List<ReservationStatus> statuses = statusesOfTab(tab);
        List<Reservation> reservations = new ArrayList<>(
                reservationRepository.findByUserIdAndStatusIn(userId, statuses));

        boolean upcoming = "upcoming".equalsIgnoreCase(tab);
        if (upcoming) {
            reservations.sort(Comparator.comparing(Reservation::getReserveDate)
                    .thenComparing(Reservation::getStartSlot));
        } else {
            reservations.sort(Comparator.comparing(Reservation::getReserveDate)
                    .thenComparing(Reservation::getStartSlot)
                    .reversed());
        }

        Map<Long, String> usernameMap = buildUsernameMap(userId);
        List<ReservationDTO.ReservationVO> vos = reservations.stream()
                .map(r -> toVO(r, usernameMap, null, null))
                .collect(Collectors.toList());
        return PageResult.of(vos, page, size);
    }

    /**
     * 预约详情（★ 非本人返回 4040）。
     *
     * @param userId        当前用户 ID
     * @param reservationId 预约 ID
     * @return 预约 VO
     */
    @Transactional(readOnly = true)
    public ReservationDTO.ReservationVO detail(Long userId, Long reservationId) {
        Reservation reservation = requireOwnReservation(userId, reservationId);
        return toVO(reservation, buildUsernameMap(userId), null, null);
    }

    /**
     * 构造「用户 ID → 账号」映射，使本人预约的 VO 也带上 username。
     *
     * @param userId 当前用户 ID
     * @return 单条映射；用户不存在时为空 Map
     */
    private Map<Long, String> buildUsernameMap(Long userId) {
        Map<Long, String> map = new HashMap<>();
        if (userId == null) {
            return map;
        }
        userRepository.findById(userId).ifPresent(user -> map.put(user.getId(), user.getUsername()));
        return map;
    }

    /**
     * 取消预约：仅开始前可取消。
     *
     * @param userId        当前用户 ID
     * @param reservationId 预约 ID
     * @return 操作结果
     */
    @Transactional
    public ReservationDTO.ReservationActionVO cancel(Long userId, Long reservationId) {
        Reservation reservation = requireOwnReservation(userId, reservationId);
        ReservationStatus status = reservation.getStatus();
        LocalDateTime now = LocalDateTime.now();

        if (status == ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "已签到后不可取消");
        }
        if (status == ReservationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "预约已结束，不可取消");
        }
        if (status == ReservationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约已取消，无需重复操作");
        }
        if (status == ReservationStatus.NO_SHOW) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约因未签到已失效，不可取消");
        }
        if (status == ReservationStatus.ADMIN_CANCELLED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约已被管理员取消，不可取消");
        }
        LocalDateTime start = BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot());
        if (!now.isBefore(start)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "预约已开始，不可取消");
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelTime(now);
        reservationRepository.save(reservation);
        // slot 行物理删除 → 唯一索引天然可复用
        reservationSlotRepository.deleteByReservationId(reservation.getId());

        return ReservationDTO.ReservationActionVO.builder()
                .reservationId(reservation.getId())
                .status(ReservationStatus.CANCELLED.name())
                .cancelTime(BizConstants.formatDateTime(now))
                .build();
    }

    /**
     * 签到：仅限开始前 15 分钟 ~ 开始后 15 分钟。
     *
     * @param userId        当前用户 ID
     * @param reservationId 预约 ID
     * @return 操作结果
     */
    @Transactional
    public ReservationDTO.ReservationActionVO checkIn(Long userId, Long reservationId) {
        Reservation reservation = requireOwnReservation(userId, reservationId);
        ReservationStatus status = reservation.getStatus();
        LocalDateTime now = LocalDateTime.now();

        if (status == ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "已签到，无需重复签到");
        }
        if (status == ReservationStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约已取消，不可签到");
        }
        if (status == ReservationStatus.ADMIN_CANCELLED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约已被管理员取消，不可签到");
        }
        if (status == ReservationStatus.NO_SHOW) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约因超时未签到已失效，不可签到");
        }
        if (status == ReservationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约已结束，不可签到");
        }

        LocalDateTime start = BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot());
        LocalDateTime openAt = start.minusMinutes(ruleConfigService.checkInBeforeMin());
        LocalDateTime closeAt = start.plusMinutes(ruleConfigService.checkInAfterMin());
        if (now.isBefore(openAt)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "未到签到时间，" + openAt.format(HM_FORMATTER) + " 后可签到");
        }
        if (now.isAfter(closeAt)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "已超过签到时间（签到窗口 " + openAt.format(HM_FORMATTER) + "-" + closeAt.format(HM_FORMATTER) + "）");
        }

        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setCheckInTime(now);
        reservationRepository.save(reservation);

        return ReservationDTO.ReservationActionVO.builder()
                .reservationId(reservation.getId())
                .status(ReservationStatus.CHECKED_IN.name())
                .checkInTime(BizConstants.formatDateTime(now))
                .build();
    }

    /**
     * 取本人预约，非本人或不存在均返回 4040（防水平越权）。
     *
     * @param userId        当前用户 ID
     * @param reservationId 预约 ID
     * @return 预约实体
     */
    @Transactional(readOnly = true)
    public Reservation requireOwnReservation(Long userId, Long reservationId) {
        if (reservationId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约 ID 不能为空");
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "预约不存在");
        }
        return reservation;
    }

    /**
     * 把预约实体转换为 VO（含后端计算的 canCancel / canCheckIn / checkInWindowText）。
     *
     * @param reservation   预约实体
     * @param usernameMap   用户 ID → 账号（管理员端可传，用户端传 null）
     * @param realNameMap   用户 ID → 姓名
     * @param seatMap       座位 ID → 座位（传 null 时按需查询）
     * @return 预约 VO
     */
    @Transactional(readOnly = true)
    public ReservationDTO.ReservationVO toVO(Reservation reservation,
                                             Map<Long, String> usernameMap,
                                             Map<Long, String> realNameMap,
                                             Map<Long, Seat> seatMap) {
        Seat seat = null;
        if (seatMap != null) {
            seat = seatMap.get(reservation.getSeatId());
        }
        if (seat == null) {
            seat = seatRepository.findById(reservation.getSeatId()).orElse(null);
        }

        String floorName = null;
        if (seat != null) {
            floorName = floorService.requireFloor(seat.getFloorId()).getName();
        }

        LocalDateTime now = LocalDateTime.now();
        ReservationStatus status = reservation.getStatus();
        LocalDateTime start = BizConstants.slotStart(reservation.getReserveDate(), reservation.getStartSlot());
        LocalDateTime openAt = start.minusMinutes(ruleConfigService.checkInBeforeMin());
        LocalDateTime closeAt = start.plusMinutes(ruleConfigService.checkInAfterMin());

        boolean canCancel = status == ReservationStatus.CONFIRMED && now.isBefore(start);
        boolean canCheckIn = status == ReservationStatus.CONFIRMED
                && !now.isBefore(openAt) && !now.isAfter(closeAt);

        String windowText;
        if (status != ReservationStatus.CONFIRMED) {
            windowText = "--";
        } else if (now.isBefore(openAt)) {
            windowText = openAt.format(HM_FORMATTER) + " 后可签到";
        } else if (now.isAfter(closeAt)) {
            windowText = "签到已截止（窗口 " + openAt.format(HM_FORMATTER) + "-" + closeAt.format(HM_FORMATTER) + "）";
        } else {
            windowText = "签到窗口 " + openAt.format(HM_FORMATTER) + "-" + closeAt.format(HM_FORMATTER);
        }

        int duration = reservation.getEndSlot() - reservation.getStartSlot();
        String username = (usernameMap == null) ? null : usernameMap.get(reservation.getUserId());

        return ReservationDTO.ReservationVO.builder()
                .reservationId(reservation.getId())
                .userId(reservation.getUserId())
                .username(username)
                .seatId(reservation.getSeatId())
                .seatNo(seat == null ? null : seat.getSeatNo())
                .zone(seat == null ? null : seat.getZone())
                .floorId(reservation.getFloorId())
                .floorName(floorName)
                .date(BizConstants.formatDate(reservation.getReserveDate()))
                .startSlot(reservation.getStartSlot())
                .endSlot(reservation.getEndSlot())
                .timeRange(BizConstants.rangeLabel(reservation.getStartSlot(), duration))
                .durationHours(duration)
                .status(status.name())
                .statusText(status.getText())
                .checkInTime(BizConstants.formatDateTime(reservation.getCheckInTime()))
                .cancelTime(BizConstants.formatDateTime(reservation.getCancelTime()))
                .adminCancelReason(reservation.getAdminCancelReason())
                .createTime(BizConstants.formatDateTime(reservation.getCreateTime()))
                .canCancel(canCancel)
                .canCheckIn(canCheckIn)
                .checkInWindowText(windowText)
                .build();
    }

    /**
     * 分组 → 状态集合。
     *
     * @param tab 分组名
     * @return 状态集合
     */
    public List<ReservationStatus> statusesOfTab(String tab) {
        String key = (tab == null || tab.isBlank()) ? "upcoming" : tab.trim().toLowerCase();
        return switch (key) {
            case "inprogress", "in_progress" -> List.of(ReservationStatus.CHECKED_IN);
            case "finished" -> List.of(ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW);
            case "cancelled" -> List.of(ReservationStatus.CANCELLED, ReservationStatus.ADMIN_CANCELLED);
            default -> List.of(ReservationStatus.CONFIRMED);
        };
    }

    /**
     * 批量转换（管理员端复用）。
     *
     * @param reservations 预约列表
     * @param usernameMap  用户 ID → 账号
     * @param seatMap      座位 ID → 座位
     * @return VO 列表
     */
    @Transactional(readOnly = true)
    public List<ReservationDTO.ReservationVO> toVOList(List<Reservation> reservations,
                                                       Map<Long, String> usernameMap,
                                                       Map<Long, Seat> seatMap) {
        List<ReservationDTO.ReservationVO> list = new ArrayList<>(reservations.size());
        for (Reservation reservation : reservations) {
            list.add(toVO(reservation, usernameMap, null, seatMap));
        }
        return list;
    }
}

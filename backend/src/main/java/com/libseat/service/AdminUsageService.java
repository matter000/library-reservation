package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.PageResult;
import com.libseat.dto.AdminDTO;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.entity.User;
import com.libseat.enums.NoticeType;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员·使用情况 / 预约记录 / 强制释放。
 *
 * <p>隐私红线：占用人只返回 realName + username，<b>绝不返回手机号</b>。
 */
@Service
@RequiredArgsConstructor
public class AdminUsageService {

    private final SeatRepository seatRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final UserRepository userRepository;

    private final OccupancyService occupancyService;

    private final FloorService floorService;

    private final AuditService auditService;

    /** 候补顶位（馆员释放与用户取消走同一条路径，T-V2-19）。 */
    private final WaitlistStateService waitlistStateService;

    private final NoticeService noticeService;

    /**
     * 座位使用情况查询。
     *
     * @param floorId 楼层 ID
     * @param date    日期
     * @param slot    时段序号
     * @param only    过滤：all / occupied / free
     * @return 使用情况
     */
    @Transactional(readOnly = true)
    public AdminDTO.UsageVO usage(Long floorId, LocalDate date, Integer slot, String only) {
        Floor floor = floorService.requireFloor(floorId);
        LocalDate targetDate = (date == null) ? LocalDate.now() : date;
        int targetSlot = (slot == null) ? floorService.currentSlot() : BizConstants.clampToSlotRange(slot);

        List<Seat> seats = seatRepository.findByFloorIdOrderByRowNoAscColNoAsc(floorId);
        Map<Long, OccupancyService.SeatOccupancy> occupiedMap = occupancyService.getOccupancy(targetDate, targetSlot);
        Map<Long, Reservation> reservationMap = loadActiveReservations(targetDate, targetSlot);
        Map<Long, AdminDTO.OccupantVO> occupantMap = loadOccupants(reservationMap);

        String mode = (only == null || only.isBlank()) ? "all" : only.trim().toLowerCase();
        long total = seats.size();
        long occupied = 0;
        long free = 0;
        long unavailable = 0;

        List<AdminDTO.UsageRowVO> rows = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            SeatDisplayStatus display = occupancyService.derive(
                    seat, floor.getStatus() == com.libseat.enums.FloorStatus.CLOSED, occupiedMap, targetSlot);
            if (display == SeatDisplayStatus.UNAVAILABLE) {
                unavailable++;
            } else if (display.occupying()) {
                occupied++;
            } else {
                free++;
            }
            if ("occupied".equals(mode) && !display.occupying()) {
                continue;
            }
            if ("free".equals(mode) && display != SeatDisplayStatus.FREE) {
                continue;
            }

            Reservation reservation = reservationMap.get(seat.getId());
            AdminDTO.OccupantVO occupant = (reservation == null) ? null : occupantMap.get(reservation.getUserId());
            SeatStatus baseStatus = seat.getStatus() == null ? SeatStatus.AVAILABLE : seat.getStatus();
            rows.add(AdminDTO.UsageRowVO.builder()
                    .seatId(seat.getId())
                    .seatNo(seat.getSeatNo())
                    .zone(seat.getZone())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .baseStatus(baseStatus.name())
                    .displayStatus(display.name())
                    .occupant(occupant)
                    .reservationId(reservation == null ? null : reservation.getId())
                    .startSlot(reservation == null ? null : reservation.getStartSlot())
                    .endSlot(reservation == null ? null : reservation.getEndSlot())
                    .timeRange(reservation == null ? null
                            : BizConstants.rangeLabel(reservation.getStartSlot(),
                            BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot())))
                    .reservationStatus(reservation == null ? null : reservation.getStatus().name())
                    .build());
        }

        AdminDTO.UsageSummaryVO summary = AdminDTO.UsageSummaryVO.builder()
                .total(total)
                .occupied(occupied)
                .free(free)
                .unavailable(unavailable)
                .usageRate(BizConstants.rate(occupied, occupied + free))
                .build();

        return AdminDTO.UsageVO.builder()
                .floorId(floor.getId())
                .floorName(floor.getName())
                .date(BizConstants.formatDate(targetDate))
                .slotIndex(targetSlot)
                .slotLabel(BizConstants.slotLabel(targetSlot))
                .summary(summary)
                .rows(rows)
                .build();
    }

    /**
     * 预约记录分页查询（默认创建时间倒序）。
     *
     * @param query 查询条件
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<AdminDTO.AdminReservationVO> reservations(AdminDTO.AdminReservationQuery query) {
        int page = Math.max(query.getPage() == null ? 1 : query.getPage(), 1);
        int size = query.getSize() == null || query.getSize() <= 0 ? 20 : query.getSize();

        // 关键字 → 用户 ID 集合 / 座位 ID 集合
        List<Long> userIds = new ArrayList<>();
        List<Long> seatIds = new ArrayList<>();
        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            for (User user : userRepository.findByUsernameContainingOrRealNameContaining(keyword, keyword)) {
                userIds.add(user.getId());
            }
            for (Seat seat : seatRepository.findBySeatNoContaining(keyword)) {
                seatIds.add(seat.getId());
            }
        }
        ReservationStatus status = parseStatus(query.getStatus());
        LocalDate dateFrom = query.getDateFrom();
        LocalDate dateTo = query.getDateTo();
        Long floorId = query.getFloorId();

        Specification<Reservation> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (floorId != null) {
                predicates.add(cb.equal(root.get("floorId"), floorId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("reserveDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("reserveDate"), dateTo));
            }
            if (StringUtils.hasText(query.getKeyword())) {
                List<Predicate> keywordPredicates = new ArrayList<>();
                if (!userIds.isEmpty()) {
                    keywordPredicates.add(root.get("userId").in(userIds));
                }
                if (!seatIds.isEmpty()) {
                    keywordPredicates.add(root.get("seatId").in(seatIds));
                }
                if (keywordPredicates.isEmpty()) {
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(cb.or(keywordPredicates.toArray(new Predicate[0])));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Reservation> pageResult = reservationRepository.findAll(
                spec, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createTime")));

        List<Reservation> content = pageResult.getContent();
        Map<Long, String> usernameMap = new HashMap<>();
        Map<Long, String> realNameMap = new HashMap<>();
        Map<Long, String> studentNoMap = new HashMap<>();
        Map<Long, Seat> seatMap = new HashMap<>();
        for (Reservation reservation : content) {
            if (!usernameMap.containsKey(reservation.getUserId())) {
                userRepository.findById(reservation.getUserId()).ifPresent(user -> {
                    usernameMap.put(user.getId(), user.getUsername());
                    realNameMap.put(user.getId(), user.getRealName());
                    studentNoMap.put(user.getId(), user.getStudentNo());
                });
            }
            if (!seatMap.containsKey(reservation.getSeatId())) {
                seatRepository.findById(reservation.getSeatId()).ifPresent(seat -> seatMap.put(seat.getId(), seat));
            }
        }

        List<AdminDTO.AdminReservationVO> list = new ArrayList<>(content.size());
        for (Reservation reservation : content) {
            Seat seat = seatMap.get(reservation.getSeatId());
            String floorName = null;
            if (seat != null) {
                try {
                    floorName = floorService.requireFloor(seat.getFloorId()).getName();
                } catch (BusinessException e) {
                    floorName = null;
                }
            }
            int duration = BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot());
            list.add(AdminDTO.AdminReservationVO.builder()
                    .reservationId(reservation.getId())
                    .userId(reservation.getUserId())
                    .realName(realNameMap.get(reservation.getUserId()))
                    .studentNo(studentNoMap.get(reservation.getUserId()))
                    .username(usernameMap.get(reservation.getUserId()))
                    .floorId(reservation.getFloorId())
                    .floorName(floorName)
                    .seatId(reservation.getSeatId())
                    .seatNo(seat == null ? null : seat.getSeatNo())
                    .date(BizConstants.formatDate(reservation.getReserveDate()))
                    .startSlot(reservation.getStartSlot())
                    .endSlot(reservation.getEndSlot())
                    .timeRange(BizConstants.rangeLabel(reservation.getStartSlot(), duration))
                    .durationHours(duration)
                    .status(reservation.getStatus().name())
                    .statusText(reservation.getStatus().getText())
                    .checkInTime(BizConstants.formatDateTime(reservation.getCheckInTime()))
                    .cancelTime(BizConstants.formatDateTime(reservation.getCancelTime()))
                    .adminCancelReason(reservation.getAdminCancelReason())
                    .createTime(BizConstants.formatDateTime(reservation.getCreateTime()))
                    .canRelease(reservation.getStatus() == ReservationStatus.CONFIRMED
                            || reservation.getStatus() == ReservationStatus.CHECKED_IN)
                    .build());
        }
        return PageResult.<AdminDTO.AdminReservationVO>builder()
                .list(list)
                .total(pageResult.getTotalElements())
                .page(page)
                .size(size)
                .build();
    }

    /**
     * ★ 强制释放：状态置 ADMIN_CANCELLED，写原因与时间，物理删除 slot，不加违约次数。
     *
     * @param reservationId 预约 ID
     * @param reason        释放原因（≤100 字）
     * @return 释放结果
     */
    @Transactional
    public AdminDTO.ReleaseVO release(Long reservationId, String reason) {
        return release(reservationId, reason, null);
    }

    /**
     * 强制释放，并可指定给学生的通知正文。
     *
     * <p>★ PRD §12-26 要求「强制释放必填原因并通知学生」，所以通知由这个唯一入口发，
     *   而不是散落在各个调用方：馆员台直接释放以前只改状态不发通知，
     *   学生的座位没了却查不到是谁、为什么放的 —— 那是必然的投诉来源。
     *   调用方可以传更具体的正文（巡查处置带「有异议可申诉」），不传则用通用文案；
     *   同一（学生, 类型, 预约）只会落一条通知，所以调用方不必再自己发一次。
     *
     * @param reservationId 预约 ID
     * @param reason        释放原因（≤100 字，必填）
     * @param noticeText    给学生的通知正文，null 表示用通用文案
     * @return 释放结果
     */
    @Transactional
    public AdminDTO.ReleaseVO release(Long reservationId, String reason, String noticeText) {
        if (reservationId == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约 ID 不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写释放原因");
        }
        if (reason.trim().length() > 100) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "释放原因不能超过 100 字");
        }
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该预约当前状态不可释放");
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String previousStatus = reservation.getStatus().name();
        reservation.setStatus(ReservationStatus.ADMIN_CANCELLED);
        reservation.setCancelTime(now);
        reservation.setAdminCancelReason(reason.trim());
        reservationRepository.save(reservation);
        reservationSlotRepository.deleteByReservationId(reservation.getId());
        // 馆员撤销是把椅子还给下一位，不是让它空着：同一事务内立刻顶位
        waitlistStateService.onReservationReleased(reservation, now);

        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("userId", reservation.getUserId());
        detail.put("seatId", reservation.getSeatId());
        detail.put("previousStatus", previousStatus);
        detail.put("reason", reason.trim());
        auditService.record(AuditService.ACTION_RESERVATION_RELEASE, "RESERVATION", reservation.getId(), detail);

        noticeService.push(reservation.getUserId(), NoticeType.SEAT_RELEASED, "座位已被释放",
                StringUtils.hasText(noticeText) ? noticeText
                        : BizConstants.formatDate(reservation.getReserveDate()) + " "
                                + BizConstants.rangeLabel(reservation.getStartSlot(),
                                BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot()))
                                + " 的座位被管理员释放，原因：" + reason.trim()
                                + "。如对处置有异议，可到馆员台当面说明或在「信用与违规」页申诉。",
                NoticeService.REF_RESERVATION, reservation.getId(), null);

        return AdminDTO.ReleaseVO.builder()
                .reservationId(reservation.getId())
                .status(ReservationStatus.ADMIN_CANCELLED.name())
                .reason(reason.trim())
                .build();
    }

    private Map<Long, Reservation> loadActiveReservations(LocalDate date, int slot) {
        Map<Long, Reservation> map = new HashMap<>();
        List<Reservation> reservations = reservationRepository.findByReserveDateAndStatusIn(
                date, OccupancyService.OCCUPYING_STATUSES);
        for (Reservation reservation : reservations) {
            if (reservation.getStartSlot() <= slot && slot < reservation.getEndSlot()) {
                map.putIfAbsent(reservation.getSeatId(), reservation);
            }
        }
        return map;
    }

    private Map<Long, AdminDTO.OccupantVO> loadOccupants(Map<Long, Reservation> reservationMap) {
        Map<Long, AdminDTO.OccupantVO> map = new HashMap<>();
        for (Reservation reservation : reservationMap.values()) {
            if (map.containsKey(reservation.getUserId())) {
                continue;
            }
            userRepository.findById(reservation.getUserId()).ifPresent(user ->
                    map.put(user.getId(), AdminDTO.OccupantVO.builder()
                            .realName(user.getRealName())
                            .studentNo(user.getStudentNo())
                            .username(user.getUsername())
                            .build()));
        }
        return map;
    }

    private ReservationStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约状态不合法");
        }
    }
}

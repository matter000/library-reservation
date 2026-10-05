package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.ReservationDTO;
import com.libseat.dto.WaitlistDTO;
import com.libseat.entity.Seat;
import com.libseat.entity.Waitlist;
import com.libseat.entity.Zone;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.enums.UserRole;
import com.libseat.enums.WaitlistStatus;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.WaitlistRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 候补队列用例（DESIGN §8.3，T-V2-19）。
 *
 * <p>排队键是「分区 + 日期 + 起始时段」，<b>不含座位</b>：释放的是「一个位置」而不是
 * 「那一把椅子」。确认时才现场挑一座并重新走完整校验链（信用 / 累计 / 重叠 / 冲突），
 * 所以候补成功约出来的预约与直接约的没有任何区别 —— 不存在第二条占座通道。
 *
 * <p>★ offer 是「轮到你尝试」而不是「给你留了座位」：窗口内座位可能被别人直接约走。
 * 因此确认失败（无空位 / 校验不过）时这一条立即作废并顺延下一位，队列自愈，
 * 而不是留一条永远确认不了的幽灵 offer。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;

    private final WaitlistStateService waitlistStateService;

    private final ZoneRepository zoneRepository;

    private final SeatRepository seatRepository;

    private final ReservationRepository reservationRepository;

    private final OccupancyService occupancyService;

    private final RuleConfigService ruleConfigService;

    private final ReservationService reservationService;

    /**
     * 加入候补。
     *
     * @param userId  本人
     * @param role    本人角色（分区资格）
     * @param request 排队请求
     * @return 队列视图
     */
    @Transactional
    public WaitlistDTO.WaitlistVO join(Long userId, UserRole role, WaitlistDTO.WaitlistJoinRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Zone zone = zoneRepository.findById(request.getZoneId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分区不存在"));
        if (zone.getStatus() != FloorStatus.OPEN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该分区已停用，不能候补");
        }
        if (!ZoneService.containsRole(zone.getEligibleRoles(), role)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE,
                    zone.getName() + " 仅对 " + zone.getEligibleRoles().replace(",", "、") + " 开放");
        }
        if (Boolean.TRUE.equals(zone.getGroupRequired())) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    zone.getName() + " 只接受整组预约，请直接建队而不是候补");
        }
        int endSlot = request.getStartSlot() + BizConstants.slotsOf(request.getDuration());
        if (!BizConstants.validSlotIndex(request.getStartSlot()) || endSlot > BizConstants.SLOT_INDEX_MAX + 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "候补时段超出开放时间");
        }
        if (request.getDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能候补已过去的日期");
        }
        if (!BizConstants.slotStart(request.getDate(), request.getStartSlot()).isAfter(now)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能候补已过去的时段");
        }
        // 时长档位与预约同一口径（教师可候补 5/6 小时），不要在候补这条路上另写一份 1~4
        if (!ruleConfigService.allowedDurations(role).contains(request.getDuration())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "时长只能选择 " + ruleConfigService.allowedDurations(role) + " 小时");
        }

        long freeNow = freeSeats(zone, request.getDate(), request.getStartSlot(), endSlot).size();
        if (freeNow > 0) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该时段还有 " + freeNow + " 个空位，请直接预约而不是排队");
        }
        if (!waitlistRepository.findByUserIdAndZoneIdAndReserveDateAndStartSlotAndActiveFlag(
                userId, zone.getId(), request.getDate(), request.getStartSlot(),
                WaitlistRepository.ACTIVE_FLAG).isEmpty()) {
            throw new BusinessException(ErrorCode.WAITLIST_LIMIT, "你已经在这个时段排队了");
        }
        long active = waitlistRepository.countByUserIdAndActiveFlag(userId, WaitlistRepository.ACTIVE_FLAG);
        int maxActive = ruleConfigService.waitlistMaxActive();
        if (active >= maxActive) {
            throw new BusinessException(ErrorCode.WAITLIST_LIMIT,
                    "每人最多同时挂 " + maxActive + " 条候补，请先退出其中一条");
        }

        Waitlist entry = Waitlist.builder()
                .userId(userId)
                .floorId(zone.getFloorId())
                .zoneId(zone.getId())
                .reserveDate(request.getDate())
                .startSlot(request.getStartSlot())
                .endSlot(endSlot)
                .createTime(now)
                .updateTime(now)
                .build();
        // 状态必须在首次 insert 之前定：status 是 NOT NULL，而 active_flag 只能由 changeStatus 落
        entry.changeStatus(WaitlistStatus.WAITING);
        Waitlist saved = waitlistRepository.saveAndFlush(entry);
        log.info("加入候补：waitlistId={}, userId={}, 分区={}, {} 第 {} 格起",
                saved.getId(), userId, zone.getCode(), saved.getReserveDate(), saved.getStartSlot());
        return view(saved, zone.getName(), now);
    }

    /**
     * 我的活动候补。
     *
     * @param userId 本人
     * @return 列表（新的在前）
     */
    @Transactional(readOnly = true)
    public List<WaitlistDTO.WaitlistVO> mine(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, String> zoneNames = zoneNames();
        return waitlistRepository.findByUserIdAndActiveFlagOrderByIdDesc(userId, WaitlistRepository.ACTIVE_FLAG)
                .stream()
                .map(item -> view(item, zoneNames.get(item.getZoneId()), now))
                .toList();
    }

    /**
     * 确认候补：挑一座并走完整预约校验链。
     *
     * @param userId 本人
     * @param id     候补记录
     * @return 下单结果
     */
    @Transactional
    public ReservationDTO.ReservationCreateVO confirm(Long userId, Long id) {
        LocalDateTime now = LocalDateTime.now();
        Waitlist waitlist = requireOwn(userId, id);
        if (waitlist.getStatus() != WaitlistStatus.OFFERED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "当前是「" + waitlist.getStatus().getText() + "」，不能确认");
        }
        if (waitlistStateService.offerExpired(waitlist, now)) {
            waitlistStateService.expireAndPromote(waitlist.getId(), now);
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "确认窗口已过，位置已顺延下一位");
        }

        List<Seat> free = freeSeatsOfZone(waitlist.getZoneId())
                .stream()
                .filter(seat -> isSeatFree(seat.getId(), waitlist.getReserveDate(),
                        waitlist.getStartSlot(), waitlist.getEndSlot()))
                .sorted(Comparator.comparing(Seat::getSeatNo))
                .toList();
        if (free.isEmpty()) {
            waitlistStateService.expireAndPromote(waitlist.getId(), now);
            throw new BusinessException(ErrorCode.SEAT_SLOT_CONFLICT,
                    "该时段已无空位（可能被直接约走），位置已顺延下一位");
        }

        // 先清掉自己在这段区间里的其他预约：确认候补等于改主意要坐这一格，
        // 旧预约留着就会撞「同人时段重叠」，而且那把椅子本来也不该被同时占两次。
        for (var old : reservationRepository.findOverlapping(userId,
                OccupancyService.OCCUPYING_STATUSES, waitlist.getReserveDate(),
                waitlist.getEndSlot(), waitlist.getStartSlot())) {
            reservationService.cancel(userId, old.getId());
        }

        ReservationDTO.ReservationCreateRequest request = new ReservationDTO.ReservationCreateRequest();
        request.setSeatId(free.get(0).getId());
        request.setDate(waitlist.getReserveDate());
        request.setStartSlot(waitlist.getStartSlot());
        request.setDuration(BizConstants.hoursOfSlots(waitlist.getEndSlot() - waitlist.getStartSlot()));
        try {
            ReservationDTO.ReservationCreateVO created = reservationService.createReservation(userId, request);
            waitlistStateService.markConfirmed(waitlist.getId());
            return created;
        } catch (BusinessException e) {
            // 校验没过就别占着队首：作废顺延，把原因原样回传给用户
            waitlistStateService.expireAndPromote(waitlist.getId(), LocalDateTime.now());
            throw e;
        }
    }

    /**
     * 退出队列。
     *
     * @param userId 本人
     * @param id     候补记录
     * @return 队列视图
     */
    @Transactional
    public WaitlistDTO.WaitlistVO quit(Long userId, Long id) {
        LocalDateTime now = LocalDateTime.now();
        Waitlist waitlist = requireOwn(userId, id);
        if (!waitlist.getStatus().active()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "这条候补已是「" + waitlist.getStatus().getText() + "」，无需退出");
        }
        boolean wasOffered = waitlist.getStatus() == WaitlistStatus.OFFERED;
        waitlistStateService.markCancelled(waitlist.getId());
        if (wasOffered) {
            // 手里这个位置让出来就该给下一位，不能等下一轮扫描
            waitlistStateService.onSeatReleased(waitlist.getZoneId(), waitlist.getReserveDate(),
                    waitlist.getStartSlot(), waitlist.getEndSlot(), now);
        }
        return view(waitlist, zoneNames().get(waitlist.getZoneId()), now);
    }

    private Waitlist requireOwn(Long userId, Long id) {
        Waitlist waitlist = waitlistRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "候补记录不存在"));
        if (!waitlist.getUserId().equals(userId)) {
            // 别人的候补既不能确认也不能退，按「不存在」回：确认按钮本来就不该出现在他的页面上
            throw new BusinessException(ErrorCode.NOT_FOUND, "候补记录不存在");
        }
        return waitlist;
    }

    /** 该排队键上此刻的空位数（候补的前提是「满座」）。 */
    private List<Seat> freeSeats(Zone zone, LocalDate date, int startSlot, int endSlot) {
        return freeSeatsOfZone(zone.getId()).stream()
                .filter(seat -> isSeatFree(seat.getId(), date, startSlot, endSlot))
                .toList();
    }

    private List<Seat> freeSeatsOfZone(Long zoneId) {
        List<Seat> seats = seatRepository.findByZoneId(zoneId);
        return seats.stream().filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE).toList();
    }

    /**
     * 座位在整段区间内是否都空着。
     *
     * <p>★ 只看起始格会骗人：起始格空、第二格被人占满的座位约上去就是 4091，
     *   候补确认必须按要下单的整段区间挑座。
     */
    private boolean isSeatFree(Long seatId, LocalDate date, int startSlot, int endSlot) {
        for (int slot = startSlot; slot < endSlot; slot++) {
            if (occupancyService.getOccupancy(date, slot).containsKey(seatId)) {
                return false;
            }
        }
        return true;
    }

    private Map<Long, String> zoneNames() {
        Map<Long, String> names = new HashMap<>();
        for (Zone zone : zoneRepository.findAll()) {
            names.put(zone.getId(), zone.getName());
        }
        return names;
    }

    /**
     * 组装队列视图（含位置与按钮可用性）。
     */
    private WaitlistDTO.WaitlistVO view(Waitlist waitlist, String zoneName, LocalDateTime now) {
        Integer position = null;
        int queueLength = 0;
        if (waitlist.getStatus().active()) {
            List<Waitlist> waiting = waitlistRepository
                    .findByZoneIdAndReserveDateAndStartSlotAndStatusOrderByIdAsc(
                            waitlist.getZoneId(), waitlist.getReserveDate(),
                            waitlist.getStartSlot(), WaitlistStatus.WAITING);
            List<Waitlist> offered = waitlistRepository
                    .findByZoneIdAndReserveDateAndStartSlotAndStatusOrderByIdAsc(
                            waitlist.getZoneId(), waitlist.getReserveDate(),
                            waitlist.getStartSlot(), WaitlistStatus.OFFERED);
            queueLength = waiting.size() + offered.size();
            if (waitlist.getStatus() == WaitlistStatus.OFFERED) {
                position = 1;
            } else {
                int rank = 0;
                for (Waitlist item : waiting) {
                    rank++;
                    if (item.getId().equals(waitlist.getId())) {
                        position = rank;
                        break;
                    }
                }
            }
        }
        int duration = BizConstants.hoursOfSlots(waitlist.getEndSlot() - waitlist.getStartSlot());
        boolean offered = waitlist.getStatus() == WaitlistStatus.OFFERED;
        boolean expired = offered && waitlistStateService.offerExpired(waitlist, now);
        String confirmReason = null;
        if (!offered) {
            confirmReason = "还没轮到你确认（当前「" + waitlist.getStatus().getText() + "」）";
        } else if (expired) {
            confirmReason = "确认窗口已过，位置已顺延下一位";
        }
        return WaitlistDTO.WaitlistVO.builder()
                .waitlistId(waitlist.getId())
                .floorId(waitlist.getFloorId())
                .zoneId(waitlist.getZoneId())
                .zoneName(zoneName)
                .date(BizConstants.formatDate(waitlist.getReserveDate()))
                .startSlot(waitlist.getStartSlot())
                .endSlot(waitlist.getEndSlot())
                .timeRange(BizConstants.rangeLabel(waitlist.getStartSlot(), duration))
                .durationHours(duration)
                .status(waitlist.getStatus().name())
                .statusText(waitlist.getStatus().getText())
                .position(position)
                .queueLength(queueLength)
                .offeredTime(BizConstants.formatDateTime(waitlist.getOfferedTime()))
                .offerExpireTime(BizConstants.formatDateTime(waitlist.getOfferExpireTime()))
                .minutesToExpire(waitlistStateService.minutesToExpire(waitlist, now))
                .canConfirm(offered && !expired)
                .confirmDisabledReason(confirmReason)
                .canQuit(waitlist.getStatus().active())
                .quitDisabledReason(waitlist.getStatus().active() ? null
                        : "这条候补已是「" + waitlist.getStatus().getText() + "」")
                .createTime(BizConstants.formatDateTime(waitlist.getCreateTime()))
                .build();
    }
}

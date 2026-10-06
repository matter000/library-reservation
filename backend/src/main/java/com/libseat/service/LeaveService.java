package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.LeaveDTO;
import com.libseat.dto.ReservationDTO;
import com.libseat.entity.LeaveReason;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.entity.Zone;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.NoticeType;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.LeaveReasonRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 暂离服务（DESIGN §5.2 的七步校验链）。
 *
 * <p>占用口径不变：暂离期间预约仍是 {@code CHECKED_IN}、slot 行不删，
 * 座位继续对该时段占用，只是展示态将来由 T-V2-10 派生成 {@code ON_LEAVE}。
 * 「一条预约至多一条 OPEN」由唯一索引 {@code uk_leave_open} 兜底，不靠先查后插。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    /** 时刻文案格式（全站时段文案单点，见 B2 约定）。 */
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 分区没配次数时的兜底 = PRD §6.2「每日 ≤3 次」（t_zone.leave_daily_limit 的 DDL 默认值同为 3）。 */
    private static final int DAILY_LIMIT_FALLBACK = 3;

    private final LeaveRecordRepository leaveRecordRepository;

    private final LeaveReasonRepository leaveReasonRepository;

    private final ReservationRepository reservationRepository;

    private final SeatRepository seatRepository;

    private final ZoneRepository zoneRepository;

    private final NoticeService noticeService;

    /**
     * 原因字典（前端选择器用，只返回启用项）。
     *
     * <p>★ 带上「今天这个原因已用几次」：原因各自有每日次数（就餐 2 次），
     * 而列表页的「暂离」按钮只看分区总数，选择器是用户唯一能看到原因级限制的地方。
     * 不在这里给 exhausted，用户就会选一个必然被拒的原因 —— 又回到「按钮亮着、提交被拒」。
     *
     * @param userId 当前用户（用于统计本人当日各原因次数）
     * @return 原因列表
     */
    @Transactional(readOnly = true)
    public List<LeaveDTO.ReasonVO> reasons(Long userId) {
        return reasons(userId, null);
    }

    /**
     * 原因字典，可选按"这次要暂离的是哪条预约"给出**本区生效上限**。
     *
     * <p>★ 带上 reservationId 是为了让选择器显示的数字与提交时的判定同源（B2 那轮的教训：
     * 选项与校验各算一遍，用户就会点到一个必然被拒的值）。不传则退回原因自身上限。
     *
     * @param userId        当前用户
     * @param reservationId 本人的一条使用中预约（可空）
     * @return 原因列表
     */
    @Transactional(readOnly = true)
    public List<LeaveDTO.ReasonVO> reasons(Long userId, Long reservationId) {
        Map<String, Long> usedByReason = new HashMap<>();
        if (userId != null) {
            LocalDateTime dayStart = LocalDate.now().atStartOfDay();
            leaveRecordRepository.countTodayByReason(userId, dayStart, dayStart.plusDays(1))
                    .forEach(row -> usedByReason.put((String) row[0], (Long) row[1]));
        }
        Zone zone = null;
        if (reservationId != null) {
            Reservation reservation = reservationRepository.findById(reservationId)
                    .filter(r -> r.getUserId().equals(userId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
            zone = zoneOfSeat(reservation.getSeatId());
        }
        final Zone leaveZone = zone;
        return leaveReasonRepository.findByEnabledTrueOrderBySortOrderAscCodeAsc().stream()
                .map(reason -> toReasonVO(reason, usedByReason.getOrDefault(reason.getCode(), 0L), leaveZone))
                .toList();
    }

    /**
     * 登记暂离，校验顺序严格照 DESIGN §5.2。
     *
     * @param userId  当前用户
     * @param request 登记请求
     * @return 暂离 VO
     */
    @Transactional
    public LeaveDTO.LeaveVO start(Long userId, LeaveDTO.LeaveStartRequest request) {
        // 1. 预约存在、是本人、且正在使用中（非本人与不存在同样 4040，防水平越权）
        Reservation reservation = reservationRepository.findById(request.getReservationId())
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅使用中的预约可登记暂离");
        }

        // 2. 所属分区必须允许暂离（座位无分区时沿用全局默认，视为允许）
        Zone zone = zoneOfSeat(reservation.getSeatId());
        if (zone != null && !Boolean.TRUE.equals(zone.getAllowLeave())) {
            throw new BusinessException(ErrorCode.LEAVE_NOT_ALLOWED,
                    zone.getName() + " 不支持暂离（离座即释放）");
        }

        // 3. 本人这条预约不能有进行中的暂离
        if (findOpen(reservation.getId()) != null) {
            throw new BusinessException(ErrorCode.LEAVE_ALREADY_OPEN, "当前已在暂离中，请先返回");
        }

        LeaveReason reason = leaveReasonRepository.findById(request.getReasonCode())
                .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "暂离原因不存在或已停用"));

        // 4. 当日次数：两条独立的计数口径（PRD §6.2 核心约束 1 与该节的表）——
        //    ①「每日总次数」挂在**分区**上（静音区 3、考研专座放宽至 5）；
        //    ②「该原因各自每日次数」挂在**原因表**上（就餐 2 次、接听/短休 3 次）。
        //    以前这里是 min(原因, 分区) 当总数用：六个原因都种成 3，
        //    于是考研专座的 5 永远打不开（PRD §12-13 落空），就餐的 2 次也从未生效，
        //    而「我的预约」按钮只看分区值 —— 第 4 次点亮着按钮提交却被拒，正是本类注释里
        //    禁止的「按钮亮着、提交被拒」口径分裂。拆分判定后两处口径同源（见 #dailyLimitOf）。
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        int totalLimit = dailyLimitOf(zone);
        long usedToday = leaveRecordRepository
                .countByUserIdAndStartTimeGreaterThanEqualAndStartTimeLessThan(userId, dayStart, dayEnd);
        if (usedToday >= totalLimit) {
            throw new BusinessException(ErrorCode.LEAVE_LIMIT_EXCEEDED,
                    "今日暂离次数已用完（上限 " + totalLimit + " 次）");
        }
        int reasonLimit = reason.getDailyLimit() == null ? Integer.MAX_VALUE : reason.getDailyLimit();
        long usedReasonToday = leaveRecordRepository
                .countByUserIdAndReasonCodeAndStartTimeGreaterThanEqualAndStartTimeLessThan(
                        userId, reason.getCode(), dayStart, dayEnd);
        if (usedReasonToday >= reasonLimit) {
            throw new BusinessException(ErrorCode.LEAVE_LIMIT_EXCEEDED,
                    "「" + reason.getName() + "」今日已用完 " + reasonLimit + " 次，请换一个原因");
        }

        // 5. 需要事由的原因必须写清楚
        if (Boolean.TRUE.equals(reason.getNeedNote()) && !StringUtils.hasText(request.getNote())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "「" + reason.getName() + "」需要填写事由");
        }

        // 6. 应返回时刻：先按本区口径合成单次上限（原因 × 分区，见 #effectiveLeaveMaxMin），再按剩余封顶
        //    剩余不足时按剩余封顶而不是报错（PRD §6.2-4），界面据此显示「仅剩 N 分钟」
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reservationEnd = BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot());
        LocalDateTime plannedEnd = minNonNull(
                now.plusMinutes(effectiveLeaveMaxMin(reason, zone)),
                reservationEnd);

        // 7. 落库；并发下由 uk_leave_open 兜底
        LeaveRecord record = LeaveRecord.builder()
                .reservationId(reservation.getId())
                .userId(userId)
                .seatId(reservation.getSeatId())
                .reasonCode(reason.getCode())
                .note(trimToNull(request.getNote()))
                .startTime(now)
                .plannedEndTime(plannedEnd)
                .status(LeaveStatus.OPEN)
                .openFlag(LeaveStatus.OPEN_FLAG)
                .build();
        try {
            LeaveRecord saved = leaveRecordRepository.saveAndFlush(record);
            // 暂离登记即时告知（DESIGN §5.2 第 7 步）：把「什么时候必须回来」和
            // 「剩余时间不顺延」写在正文里，因为这两条正是用户回来后发现座位已没时的争执点。
            noticeService.push(userId, NoticeType.LEAVE_OPEN,
                    "暂离已登记",
                    "请于 " + HM.format(plannedEnd) + " 前点「我已返回」。暂离期间座位仍归你，"
                            + "但本次预约的结束时刻不变（" + HM.format(reservationEnd) + " 释放），"
                            + "超时未归会记一次违规并释放座位。",
                    NoticeService.REF_LEAVE, saved.getId(), plannedEnd);
            return toVO(saved, reservation, reason);
        } catch (DataIntegrityViolationException e) {
            log.warn("并发登记暂离被唯一索引拦下：reservationId={}, userId={}", reservation.getId(), userId);
            throw new BusinessException(ErrorCode.LEAVE_ALREADY_OPEN, "当前已在暂离中，请先返回");
        }
    }

    /**
     * 我已返回：置 RETURNED、open_flag 归 NULL。
     *
     * <p>★ 剩余使用时间不顺延（PRD §6.2-7）：预约的结束时刻一个字段都不动，
     * 返回后座位仍按原时刻释放，界面必须把这点讲清楚。
     *
     * @param userId  当前用户
     * @param leaveId 暂离 ID
     * @return 暂离 VO
     */
    @Transactional
    public LeaveDTO.LeaveVO returnBack(Long userId, Long leaveId) {
        LeaveRecord record = leaveRecordRepository.findById(leaveId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "暂离记录不存在"));
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "只能结束本人的暂离");
        }
        if (record.getStatus() != LeaveStatus.OPEN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该暂离已结束，无需重复操作");
        }
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(LeaveStatus.RETURNED);
        record.setOpenFlag(null);
        record.setActualReturnTime(now);
        LeaveRecord saved = leaveRecordRepository.save(record);

        Reservation reservation = reservationRepository.findById(saved.getReservationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "预约不存在"));
        LeaveReason reason = leaveReasonRepository.findById(saved.getReasonCode()).orElse(null);
        return toVO(saved, reservation, reason);
    }

    /**
     * 某条预约当前进行中的暂离。
     *
     * @param reservationId 预约 ID
     * @return OPEN 记录，没有则 null
     */
    @Transactional(readOnly = true)
    public LeaveRecord findOpen(Long reservationId) {
        return leaveRecordRepository
                .findByReservationIdAndOpenFlag(reservationId, LeaveStatus.OPEN_FLAG)
                .orElse(null);
    }

    /**
     * 给「我的预约」列表补齐暂离相关按钮的可用性（T-V2-10：可用性一律后端算）。
     *
     * <p>★ 判定条件与 {@link #start} 的校验链同源，避免「按钮亮着、提交被拒」的口径分裂；
     * 每日次数在这里按分区上限判断（原因各自的上限仍在提交时校验），
     * 因为列表此刻还不知道用户会选哪个原因。
     *
     * @param userId 当前用户
     * @param vos    已组装好的预约 VO 列表（原地写入新增字段）
     */
    @Transactional(readOnly = true)
    public void enrichOwnerActions(Long userId, List<ReservationDTO.ReservationVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        List<Long> reservationIds = vos.stream()
                .map(ReservationDTO.ReservationVO::getReservationId)
                .toList();
        Map<Long, LeaveRecord> openByReservation = leaveRecordRepository
                .findByReservationIdInAndOpenFlag(reservationIds, LeaveStatus.OPEN_FLAG).stream()
                .collect(Collectors.toMap(LeaveRecord::getReservationId, r -> r, (a, b) -> a));

        List<Long> seatIds = vos.stream()
                .map(ReservationDTO.ReservationVO::getSeatId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Zone> zoneBySeat = new HashMap<>();
        if (!seatIds.isEmpty()) {
            Map<Long, Long> zoneIdBySeat = new HashMap<>();
            seatRepository.findAllById(seatIds).forEach(seat -> {
                if (seat.getZoneId() != null) {
                    zoneIdBySeat.put(seat.getId(), seat.getZoneId());
                }
            });
            if (!zoneIdBySeat.isEmpty()) {
                Map<Long, Zone> zones = zoneRepository.findAllById(zoneIdBySeat.values()).stream()
                        .collect(Collectors.toMap(Zone::getId, z -> z, (a, b) -> a));
                zoneIdBySeat.forEach((seatId, zoneId) -> {
                    Zone zone = zones.get(zoneId);
                    if (zone != null) {
                        zoneBySeat.put(seatId, zone);
                    }
                });
            }
        }

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long usedToday = leaveRecordRepository
                .countByUserIdAndStartTimeGreaterThanEqualAndStartTimeLessThan(userId, dayStart, dayStart.plusDays(1));
        LocalDateTime now = LocalDateTime.now();

        for (ReservationDTO.ReservationVO vo : vos) {
            LeaveRecord open = openByReservation.get(vo.getReservationId());
            Zone zone = zoneBySeat.get(vo.getSeatId());
            boolean checkedIn = ReservationStatus.CHECKED_IN.name().equals(vo.getStatus());

            vo.setCanReturn(open != null);
            vo.setCanFinish(checkedIn && open == null);
            if (!checkedIn) {
                vo.setCanLeave(false);
                vo.setCanFinish(false);
                vo.setLeaveDisabledReason(ReservationStatus.CONFIRMED.name().equals(vo.getStatus())
                        ? "签到之后才能登记暂离"
                        : "预约已结束，不能再暂离");
            } else if (open != null) {
                vo.setCanLeave(false);
                vo.setLeaveDisabledReason("当前已在暂离中，请先返回");
            } else if (zone != null && !Boolean.TRUE.equals(zone.getAllowLeave())) {
                vo.setCanLeave(false);
                vo.setLeaveDisabledReason(zone.getName() + " 不支持暂离（离座即释放）");
            } else if (usedToday >= dailyLimitOf(zone)) {
                vo.setCanLeave(false);
                vo.setLeaveDisabledReason("今日暂离次数已用完（上限 " + dailyLimitOf(zone) + " 次）");
            } else {
                vo.setCanLeave(true);
                vo.setLeaveDisabledReason(null);
            }

            if (open != null) {
                vo.setActiveLeaveId(open.getId());
                vo.setExpectedReturnAt(BizConstants.formatTime(open.getPlannedEndTime()));
                vo.setLeaveRemainMin((int) Duration.between(now, open.getPlannedEndTime()).toMinutes());
            }
        }
    }

    /**
     * 单次暂离的生效上限（分钟）：原因上限与本区上限的合成口径（PRD §12-7 / §12-12，B8）。
     *
     * <p>★ 只有 {@code relaxable} 的原因（就餐/上课）允许被**分区拉长**：考研专座把 60 分钟的
     *   「就餐」放宽到 120。其余短暂离级（接听/短休/取物/其他）仍只能被分区**收紧** ——
     *   否则「取物 15 分钟」在考研专座里也变成 120 分钟，§12-12 的原因级上限就整条失效。
     *   这与 §14.1 的分区口径一致：分区值是本区口径，可以取代全局默认，但不能把"本来就短的原因"喊长。
     *
     * <p>★ 合成只允许发生在这一个方法里：原因字典（{@link #reasons(Long, Long)}）与登记暂离
     *   （{@link #start}）读同一个出处，否则又会出现「选择器显示 60 分钟、提交却给 120」这类分裂。
     *
     * @param reason 暂离原因
     * @param zone   座位所属分区（可为 null，按原因自身上限）
     * @return 生效上限（分钟）
     */
    static int effectiveLeaveMaxMin(LeaveReason reason, Zone zone) {
        int reasonMax = reason.getMaxMin() == null ? 0 : reason.getMaxMin();
        Integer zoneMax = zone == null ? null : zone.getLeaveMaxMin();
        if (zoneMax == null || zoneMax <= 0) {
            return reasonMax;
        }
        return Boolean.TRUE.equals(reason.getRelaxable())
                ? Math.max(reasonMax, zoneMax)
                : Math.min(reasonMax, zoneMax);
    }

    private Zone zoneOfSeat(Long seatId) {
        if (seatId == null) {
            return null;
        }
        Seat seat = seatRepository.findById(seatId).orElse(null);
        if (seat == null || seat.getZoneId() == null) {
            return null;
        }
        return zoneRepository.findById(seat.getZoneId()).orElse(null);
    }

    /**
     * 取非空参数中的最早时刻（预约结束时刻永远存在，故结果必非空）。
     */
    private LocalDateTime minNonNull(LocalDateTime... candidates) {
        LocalDateTime best = null;
        for (LocalDateTime candidate : candidates) {
            if (candidate != null && (best == null || candidate.isBefore(best))) {
                best = candidate;
            }
        }
        return best;
    }

    private String trimToNull(String note) {
        return StringUtils.hasText(note) ? note.trim() : null;
    }

    /**
     * 当日暂离总次数上限：分区是这条规则的载体（考研专座放宽到 5），
     * 座位没有分区时用全局默认值，保证「无分区旧数据」不会因为缺列而被夹成 0。
     */
    private int dailyLimitOf(Zone zone) {
        if (zone == null || zone.getLeaveDailyLimit() == null) {
            return DAILY_LIMIT_FALLBACK;
        }
        return zone.getLeaveDailyLimit();
    }

    private LeaveDTO.ReasonVO toReasonVO(LeaveReason reason, long usedToday, Zone zone) {
        int limit = reason.getDailyLimit() == null ? Integer.MAX_VALUE : reason.getDailyLimit();
        boolean exhausted = usedToday >= limit;
        return LeaveDTO.ReasonVO.builder()
                .code(reason.getCode())
                .name(reason.getName())
                .maxMin(effectiveLeaveMaxMin(reason, zone))
                .relaxable(Boolean.TRUE.equals(reason.getRelaxable()))
                .dailyLimit(reason.getDailyLimit())
                .needNote(Boolean.TRUE.equals(reason.getNeedNote()))
                .todayUsed((int) usedToday)
                .exhausted(exhausted)
                .exhaustedReason(exhausted
                        ? "「" + reason.getName() + "」今日 " + limit + " 次已用完，请换一个原因"
                        : null)
                .build();
    }

    /**
     * 组装 VO：{@code remainMin} 用「应返回 - 现在」，已返回则给实际耗时。
     */
    private LeaveDTO.LeaveVO toVO(LeaveRecord record, Reservation reservation, LeaveReason reason) {
        boolean returned = record.getStatus() != LeaveStatus.OPEN;
        LocalDateTime reference = returned && record.getActualReturnTime() != null
                ? record.getActualReturnTime() : LocalDateTime.now();
        // 超时不给截成 0：负数就是「已超期 N 分钟」，界面与巡查任务都需要这个信号
        int remainMin = (int) Duration.between(reference, record.getPlannedEndTime()).toMinutes();
        String seatNo = seatRepository.findById(record.getSeatId())
                .map(Seat::getSeatNo).orElse("--");
        return LeaveDTO.LeaveVO.builder()
                .leaveId(record.getId())
                .reservationId(record.getReservationId())
                .seatNo(seatNo)
                .reasonCode(record.getReasonCode())
                .reasonText(reason == null ? record.getReasonCode() : reason.getName())
                .note(record.getNote())
                .startTime(record.getStartTime().format(HM))
                .plannedEndTime(record.getPlannedEndTime().format(HM))
                .actualReturnTime(record.getActualReturnTime() == null
                        ? null : record.getActualReturnTime().format(HM))
                .remainMin(remainMin)
                .status(record.getStatus().name())
                .statusText(record.getStatus().getText())
                .returned(returned)
                .reservationEndTime(BizConstants
                        .slotEnd(reservation.getReserveDate(), reservation.getEndSlot())
                        .format(HM))
                .build();
    }
}

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

    private final LeaveRecordRepository leaveRecordRepository;

    private final LeaveReasonRepository leaveReasonRepository;

    private final ReservationRepository reservationRepository;

    private final SeatRepository seatRepository;

    private final ZoneRepository zoneRepository;

    /**
     * 原因字典（前端选择器用，只返回启用项）。
     *
     * @return 原因列表
     */
    @Transactional(readOnly = true)
    public List<LeaveDTO.ReasonVO> reasons() {
        return leaveReasonRepository.findByEnabledTrueOrderBySortOrderAscCodeAsc().stream()
                .map(this::toReasonVO)
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

        // 4. 当日次数：分区上限与原因上限取较小值
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long usedToday = leaveRecordRepository
                .countByUserIdAndStartTimeGreaterThanEqualAndStartTimeLessThan(userId, dayStart, dayStart.plusDays(1));
        int dailyLimit = reason.getDailyLimit() == null ? Integer.MAX_VALUE : reason.getDailyLimit();
        if (zone != null && zone.getLeaveDailyLimit() != null) {
            dailyLimit = Math.min(dailyLimit, zone.getLeaveDailyLimit());
        }
        if (usedToday >= dailyLimit) {
            throw new BusinessException(ErrorCode.LEAVE_LIMIT_EXCEEDED,
                    "今日暂离次数已用完（上限 " + dailyLimit + " 次）");
        }

        // 5. 需要事由的原因必须写清楚
        if (Boolean.TRUE.equals(reason.getNeedNote()) && !StringUtils.hasText(request.getNote())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "「" + reason.getName() + "」需要填写事由");
        }

        // 6. 应返回时刻：原因上限、分区上限、预约结束时刻三者取最小
        //    剩余不足时按剩余封顶而不是报错（PRD §6.2-4），界面据此显示「仅剩 N 分钟」
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reservationEnd = BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot());
        LocalDateTime plannedEnd = minNonNull(
                now.plusMinutes(reason.getMaxMin()),
                zone == null || zone.getLeaveMaxMin() == null
                        ? null : now.plusMinutes(zone.getLeaveMaxMin()),
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
            // TODO T-V2-20 通知中心：写「暂离到期前 5 分钟提醒」的站内通知（t_notice 表在 S5 建）
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
            } else if (zone != null && zone.getLeaveDailyLimit() != null && usedToday >= zone.getLeaveDailyLimit()) {
                vo.setCanLeave(false);
                vo.setLeaveDisabledReason("今日暂离次数已用完（上限 " + zone.getLeaveDailyLimit() + " 次）");
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

    private LeaveDTO.ReasonVO toReasonVO(LeaveReason reason) {
        return LeaveDTO.ReasonVO.builder()
                .code(reason.getCode())
                .name(reason.getName())
                .maxMin(reason.getMaxMin())
                .dailyLimit(reason.getDailyLimit())
                .needNote(Boolean.TRUE.equals(reason.getNeedNote()))
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

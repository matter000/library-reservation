package com.libseat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.PatrolDTO;
import com.libseat.entity.Group;
import com.libseat.entity.GroupMember;
import com.libseat.entity.User;
import com.libseat.entity.PatrolTask;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.enums.AttendStatus;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.NoticeType;
import com.libseat.enums.PatrolAction;
import com.libseat.enums.PatrolLevel;
import com.libseat.enums.PatrolStatus;
import com.libseat.enums.PatrolType;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.GroupMemberRepository;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.PatrolTaskRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 巡查任务的生成与处置（DESIGN §11，T-V2-21）。
 *
 * <p>★ 产品红线（PRD §12-25）：<b>机器只登记疑点，绝不自动释放座位</b>。
 * 本类里唯一会改座位占用的代码是 {@link #handle} 中馆员选择 {@code RELEASE} 的那条分支，
 * 而它复用强制释放入口（必填原因 + 通知 + 审计），生成器一行释放逻辑都没有。
 *
 * <p>★ 生成器每 10 分钟跑一轮，幂等靠 {@code uk_patrol_open}：同一座位已有未处理任务时直接跳过，
 * 而不是「查一遍再决定要不要发告警」。等级（HIGH/MID/LOW）由事实算出，不接受人工填写 ——
 * 否则同一件事在不同馆员手里优先级不同，看板就没有可比性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatrolService {

    private final PatrolTaskRepository patrolTaskRepository;

    private final ReservationRepository reservationRepository;

    private final SeatRepository seatRepository;

    private final UserRepository userRepository;

    private final LeaveRecordRepository leaveRecordRepository;

    private final GroupRepository groupRepository;

    private final GroupMemberRepository groupMemberRepository;

    private final RuleConfigService ruleConfigService;

    private final CreditService creditService;

    private final AdminUsageService adminUsageService;

    private final NoticeService noticeService;

    private final AuditService auditService;

    private final ViolationService violationService;

    private final ObjectMapper objectMapper;

    // ==================== 生成 ====================

    /**
     * 跑一轮三条自动规则。
     *
     * @param now 判定时刻（测试可注入，逻辑与线上一致）
     * @return 本轮新生成的任务数
     */
    @Transactional
    public int generate(LocalDateTime now) {
        return suspectedOccupy(now) + groupNoAttend(now) + overdueHolding(now);
    }

    /**
     * 规则一：签到后连续使用超过阈值、期间没有暂离、且预约还剩一段时间。
     *
     * <p>「还剩一段时间」是必要的：一张 4 小时的桌子在结束前 5 分钟被提醒毫无意义，
     * 而它会在结束那刻由规则三接管。
     */
    private int suspectedOccupy(LocalDateTime now) {
        int suspectMin = ruleConfigService.patrolSuspectMin();
        int minLeftMin = ruleConfigService.patrolMinLeftMin();
        List<Reservation> using = checkedInNow(now);
        Set<Long> onLeave = openLeaveReservationIds(using);
        int created = 0;
        for (Reservation reservation : using) {
            if (reservation.getCheckInTime() == null || onLeave.contains(reservation.getId())) {
                continue;
            }
            long usedMin = Duration.between(reservation.getCheckInTime(), now).toMinutes();
            long leftMin = Duration.between(now,
                    BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot())).toMinutes();
            if (usedMin < suspectMin || leftMin < minLeftMin) {
                continue;
            }
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("rule", "patrol.suspect-min");
            evidence.put("thresholdMin", suspectMin);
            evidence.put("usedMin", usedMin);
            evidence.put("checkInTime", BizConstants.formatDateTime(reservation.getCheckInTime()));
            evidence.put("plannedEnd", BizConstants.formatDateTime(
                    BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot())));
            evidence.put("onLeave", false);
            evidence.put("judgedAt", BizConstants.formatDateTime(now));
            // 达到阈值就是报警线（PRD §6.5「连续使用超过 2 小时」），因此直接给 HIGH：
            // 馆员看板要按等级排序，把「最可能白占着座位」的排最前。
            PatrolLevel level = PatrolLevel.HIGH;
            if (open(reservation, PatrolType.SUSPECTED_OCCUPY, level,
                    "已连续使用 " + usedMin + " 分钟未暂离（阈值 " + suspectMin + " 分钟），"
                            + "预约 " + BizConstants.slotText(reservation.getEndSlot()) + " 结束",
                    evidence, now) != null) {
                created++;
            }
        }
        return created;
    }

    /**
     * 规则二：队伍已签到，但成员已被判为未入座。
     *
     * <p>★ 触发点取 {@code ABSENT} 而不是初稿写的「签到后 30 分钟仍是 {@code UNKNOWN}」：
     * 缺席扫描每分钟一次，{@code UNKNOWN} 最多存活一分钟后就变成 {@code ABSENT}，
     * 十分钟一轮的巡查永远抓不到它。而「组长替全队签了到、这个人到底在不在座位上」
     * 正是馆员要现场确认的事，ABSENT 就是那条事实。
     */
    private int groupNoAttend(LocalDateTime now) {
        int absentMin = ruleConfigService.patrolGroupAbsentMin();
        int created = 0;
        for (Group group : groupRepository.findByStatus(GroupStatus.CHECKED_IN)) {
            Reservation leader = leaderReservation(group);
            if (leader == null || leader.getCheckInTime() == null
                    || Duration.between(leader.getCheckInTime(), now).toMinutes() < absentMin) {
                continue;
            }
            for (GroupMember member : groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId())) {
                if (member.getAttendStatus() != AttendStatus.ABSENT || member.getSeatId() == null) {
                    continue;
                }
                LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("rule", "patrol.group-absent-min");
                evidence.put("thresholdMin", absentMin);
                evidence.put("groupId", group.getId());
                evidence.put("memberUserId", member.getUserId());
                evidence.put("leaderCheckInTime", BizConstants.formatDateTime(leader.getCheckInTime()));
                evidence.put("leaderCheckedInForOthers", true);
                evidence.put("judgedAt", BizConstants.formatDateTime(now));
                if (openAtSeat(member.getSeatId(), leader.getId(), PatrolType.GROUP_NO_ATTEND, PatrolLevel.HIGH,
                        "第 " + group.getId() + " 队组长已签到，该成员至今未确认入座（宽限 " + absentMin + " 分钟已过）",
                        evidence, now) != null) {
                    created++;
                }
            }
        }
        return created;
    }

    /**
     * 规则三：已过结束时刻但预约仍是使用中（归档任务 5 分钟一轮，中间这段就是「到期仍占」）。
     */
    private int overdueHolding(LocalDateTime now) {
        int created = 0;
        for (Reservation reservation : checkedInNow(now)) {
            LocalDateTime end = BizConstants.slotEnd(reservation.getReserveDate(), reservation.getEndSlot());
            if (end.isAfter(now)) {
                continue;
            }
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("rule", "reservation.end");
            evidence.put("plannedEnd", BizConstants.formatDateTime(end));
            evidence.put("overdueMin", Duration.between(end, now).toMinutes());
            evidence.put("status", ReservationStatus.CHECKED_IN.name());
            evidence.put("judgedAt", BizConstants.formatDateTime(now));
            if (open(reservation, PatrolType.OVERDUE_HOLDING, PatrolLevel.HIGH,
                    "预约已于 " + BizConstants.formatDateTime(end) + " 结束，座位仍被占用",
                    evidence, now) != null) {
                created++;
            }
        }
        return created;
    }

    /**
     * 馆员现场登记（规则四，也是唯一允许无预约建任务的入口）。
     *
     * @param seatId        座位
     * @param reservationId 关联预约，可为 null（无预约占用）
     * @param note          现场说明，必填
     * @param level         等级，null 时按 MID
     * @param photoUrl      照片链接，可空
     * @param operatorId    登记人
     * @return 任务视图
     */
    @Transactional
    public PatrolDTO.TaskVO registerManual(Long seatId, Long reservationId, String note,
                                           PatrolLevel level, String photoUrl, Long operatorId) {
        if (!StringUtils.hasText(note)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "人工登记必须写明现场情况");
        }
        Seat seat = requireSeat(seatId);
        LocalDateTime now = LocalDateTime.now();
        patrolTaskRepository.findBySeatIdAndOpenFlag(seat.getId(), PatrolTaskRepository.OPEN_FLAG)
                .ifPresent(exist -> {
                    throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                            "该座位已有未处理的巡查任务（#" + exist.getId() + "），请先处置它");
                });
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "manual");
        evidence.put("registeredBy", operatorId);
        evidence.put("note", note.trim());
        evidence.put("reservationId", reservationId);
        evidence.put("photoUrl", StringUtils.hasText(photoUrl) ? photoUrl.trim() : null);
        evidence.put("judgedAt", BizConstants.formatDateTime(now));
        Escalated escalated = escalate(seat.getId(), PatrolType.MANUAL,
                level == null ? PatrolLevel.MID : level, "馆员现场登记：" + note.trim(), now);
        PatrolTask saved = insertAt(seat, reservationId, PatrolType.MANUAL,
                escalated.level(), escalated.reason(), evidence, now);
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("patrolId", saved.getId());
        detail.put("seatId", seat.getId());
        detail.put("reservationId", reservationId);
        detail.put("note", note.trim());
        auditService.record(AuditService.ACTION_PATROL_REGISTER, "PATROL", saved.getId(), detail);
        log.info("人工登记巡查任务：taskId={}, seatId={}, operatorId={}", saved.getId(), seatId, operatorId);
        return view(saved, seat, relatedReservation(reservationId), reservationUserName(reservationId));
    }

    // ==================== 看板与处置 ====================

    /**
     * 看板列表。
     *
     * @param openOnly  true 只看未处理
     * @param floorId   楼层筛选，null 表示全部
     * @param level     等级筛选，null 表示全部
     * @param status    状态筛选（仅 {@code openOnly=false} 时有意义），null 表示全部
     * @return 任务视图列表（HIGH 在前，其次按生成先后）
     */
    @Transactional(readOnly = true)
    public List<PatrolDTO.TaskVO> board(boolean openOnly, Long floorId, PatrolLevel level, PatrolStatus status) {
        List<PatrolTask> tasks = openOnly
                ? patrolTaskRepository.findByOpenFlagOrderByIdAsc(PatrolTaskRepository.OPEN_FLAG)
                : patrolTaskRepository.findAll();
        List<PatrolTask> picked = new ArrayList<>();
        for (PatrolTask task : tasks) {
            if (!openOnly && (status != null ? task.getStatus() != status : task.getStatus().open())) {
                continue;
            }
            if (floorId != null && !floorId.equals(task.getFloorId())) {
                continue;
            }
            if (level != null && task.getLevel() != level) {
                continue;
            }
            picked.add(task);
        }
        // 批量补看板要展示的「椅子上是谁」：一次取预约、一次取人，而不是每行两趟查询
        List<Long> reservationIds = picked.stream()
                .map(PatrolTask::getReservationId).filter(Objects::nonNull).toList();
        Map<Long, Reservation> byReservation = reservationIds.isEmpty() ? Map.of()
                : reservationRepository.findAllById(reservationIds).stream()
                        .collect(Collectors.toMap(Reservation::getId, item -> item));
        List<Long> userIds = byReservation.values().stream()
                .map(Reservation::getUserId).distinct().toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId,
                                user -> user.getRealName() == null ? user.getUsername() : user.getRealName()));
        Map<Long, Seat> seats = picked.isEmpty() ? Map.of()
                : seatRepository.findAllById(picked.stream().map(PatrolTask::getSeatId).toList()).stream()
                        .collect(Collectors.toMap(Seat::getId, item -> item));

        List<PatrolDTO.TaskVO> out = new ArrayList<>();
        for (PatrolTask task : picked) {
            Reservation reservation = task.getReservationId() == null ? null
                    : byReservation.get(task.getReservationId());
            out.add(view(task, seats.get(task.getSeatId()), reservation,
                    reservation == null ? null : names.get(reservation.getUserId())));
        }
        out.sort((a, b) -> {
            int byLevel = rank(a.getLevel()) - rank(b.getLevel());
            return byLevel != 0 ? byLevel : a.getPatrolId().compareTo(b.getPatrolId());
        });
        return out;
    }

    /**
     * 处置一条任务。
     *
     * <p>★ 五种动作里只有 {@code RELEASE} 会动座位，而它必须先经馆员之手；
     * {@code VIOLATION} 走信用唯一入口并把本次证据链并进去，保证「裁定依据」可回放。
     *
     * @param patrolId 任务 ID
     * @param action   动作
     * @param note     处置说明（必填：看板与审计都要能回答「当时为什么这么处理」）
     * @param photoUrl 现场照片
     * @param handlerId 处置人
     * @return 处置后的任务视图
     */
    @Transactional
    public PatrolDTO.TaskVO handle(Long patrolId, PatrolAction action, String note,
                                   String photoUrl, Long handlerId) {
        PatrolTask task = patrolTaskRepository.findById(patrolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "巡查任务不存在"));
        if (!task.getStatus().open()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该任务已是「" + task.getStatus().getText() + "」，不能重复处置");
        }
        if (!StringUtils.hasText(note)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "处置必须填写说明");
        }
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = task.getReservationId() == null ? null
                : reservationRepository.findById(task.getReservationId()).orElse(null);

        if (action == PatrolAction.RELEASE) {
            if (reservation == null) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "这条任务没有关联预约，无座位可释放：请先按现场情况选「裁定违规」或「忽略」");
            }
            // 通知由强制释放这一唯一入口发（PRD §12-26），这里只把更具体的正文递进去
            adminUsageService.release(reservation.getId(), "巡查处置：" + note.trim(),
                    "馆员在巡查处置中释放了 " + BizConstants.formatDate(reservation.getReserveDate()) + " "
                            + BizConstants.rangeLabel(reservation.getStartSlot(),
                            BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot()))
                            + " 的座位，原因：" + note.trim() + "。如对处置有异议，可到馆员台或「信用与违规」页申诉。");
        } else if (action == PatrolAction.VIOLATION) {
            if (reservation == null) {
                throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                        "无预约占座请走「现场登记违规」，本入口需要能定位到被扣分的人");
            }
            LinkedHashMap<String, Object> evidence = evidenceMap(task.getEvidence());
            evidence.put("handledBy", handlerId);
            evidence.put("handleNote", note.trim());
            evidence.put("patrolId", task.getId());
            evidence.put("photoUrl", StringUtils.hasText(photoUrl) ? photoUrl.trim() : null);
            evidence.put("judgedAt", BizConstants.formatDateTime(now));
            creditService.applyViolation(reservation.getUserId(), ViolationType.ON_SITE_OCCUPY, null,
                    creditService.buildEvidence(evidence), reservation.getId(), handlerId);
        }

        task.changeStatus(action.getTargetStatus());
        task.setHandlerId(handlerId);
        task.setHandleNote(cut(note.trim(), 200));
        task.setPhotoUrl(StringUtils.hasText(photoUrl) ? cut(photoUrl.trim(), 200) : null);
        task.setHandleTime(now);
        patrolTaskRepository.save(task);

        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("patrolId", task.getId());
        detail.put("action", action.name());
        detail.put("seatId", task.getSeatId());
        detail.put("reservationId", task.getReservationId());
        detail.put("note", task.getHandleNote());
        detail.put("photoUrl", task.getPhotoUrl());
        auditService.record(AuditService.ACTION_PATROL_HANDLE, "PATROL", task.getId(), detail);
        log.info("巡查任务已处置：taskId={}, action={}, handlerId={}", patrolId, action, handlerId);
        return view(task, seatRepository.findById(task.getSeatId()).orElse(null),
                reservation, reservationUserName(task.getReservationId()));
    }

    // ==================== 内部 ====================

    /** 使用中的预约（含跨零点前的昨天记录，与爽约扫描同一取数口径）。 */
    private List<Reservation> checkedInNow(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return reservationRepository.findByStatusAndDateRange(ReservationStatus.CHECKED_IN,
                today.minusDays(1), today);
    }

    /** 此刻正在暂离的预约 ID 集合（暂离中不算连续占座）。 */
    private Set<Long> openLeaveReservationIds(List<Reservation> reservations) {
        List<Long> ids = reservations.stream().map(Reservation::getId).toList();
        Set<Long> onLeave = new HashSet<>();
        if (ids.isEmpty()) {
            return onLeave;
        }
        leaveRecordRepository.findByReservationIdInAndOpenFlag(ids, LeaveStatus.OPEN_FLAG)
                .forEach(record -> onLeave.add(record.getReservationId()));
        return onLeave;
    }

    private Reservation leaderReservation(Group group) {
        return reservationRepository.findByGroupId(group.getId()).stream()
                .filter(item -> group.getLeaderId().equals(item.getUserId()))
                .findFirst()
                .orElse(null);
    }

    /** 按预约定位座位后顶位。 */
    private PatrolTask open(Reservation reservation, PatrolType type, PatrolLevel level,
                            String reason, LinkedHashMap<String, Object> evidence, LocalDateTime now) {
        return openAtSeat(reservation.getSeatId(), reservation.getId(), type, level, reason, evidence, now);
    }

    /**
     * 该座位若已有未处理任务就跳过；等级由 {@link #escalate} 定。
     */
    private PatrolTask openAtSeat(Long seatId, Long reservationId, PatrolType type, PatrolLevel level,
                                  String reason, LinkedHashMap<String, Object> evidence, LocalDateTime now) {
        if (patrolTaskRepository.findBySeatIdAndOpenFlag(seatId, PatrolTaskRepository.OPEN_FLAG).isPresent()) {
            return null;
        }
        Seat seat = seatRepository.findById(seatId).orElse(null);
        if (seat == null) {
            return null;
        }
        Escalated escalated = escalate(seatId, type, level, reason, now);
        return insertAt(seat, reservationId, type, escalated.level(), escalated.reason(), evidence, now);
    }

    /**
     * 同一座位同类型在 {@code patrol.escalate-min} 内刚被口头提醒过，仍再次出现 → 升为 HIGH。
     *
     * <p>★ 馆员现场登记的疑点默认 MID，这条分支就是它的主要升级来源：提醒完人还坐着、下一轮又报，
     * 必须比第一次更靠前，否则口头提醒在优先级上等于没有后果。
     * 三条自动规则本来就是 HIGH，这里对它们是空转（直接返回原值）。
     */
    private Escalated escalate(Long seatId, PatrolType type, PatrolLevel level, String reason,
                               LocalDateTime now) {
        if (level == PatrolLevel.HIGH) {
            return new Escalated(level, reason);
        }
        int escalateMin = ruleConfigService.patrolEscalateMin();
        if (patrolTaskRepository.existsBySeatIdAndTypeAndStatusAndHandleTimeAfter(
                seatId, type, PatrolStatus.REMIND, now.minusMinutes(escalateMin))) {
            return new Escalated(PatrolLevel.HIGH,
                    reason + "（同一疑点 " + escalateMin + " 分钟内已被口头提醒过，等级升级）");
        }
        return new Escalated(level, reason);
    }

    /** 等级 + 依据文案，成对算出来再一起落库，避免「升了级但理由没写」。 */
    private record Escalated(PatrolLevel level, String reason) {
    }

    private PatrolTask insertAt(Seat seat, Long reservationId, PatrolType type, PatrolLevel level,
                                 String reason, LinkedHashMap<String, Object> evidence, LocalDateTime now) {
        PatrolTask saved = patrolTaskRepository.saveAndFlush(PatrolTask.builder()
                .floorId(seat.getFloorId())
                .zoneId(seat.getZoneId())
                .seatId(seat.getId())
                .reservationId(reservationId)
                .type(type)
                .level(level)
                .reason(cut(reason, 200))
                .evidence(creditService.buildEvidence(evidence))
                .createTime(now)
                .build());
        // @PrePersist 里补的 PENDING 只覆盖 insert 前为空的场景，这里显式落一次，保证状态与 open_flag 同源
        saved.changeStatus(PatrolStatus.PENDING);
        return patrolTaskRepository.saveAndFlush(saved);
    }

    private PatrolDTO.TaskVO view(PatrolTask task, Seat seat, Reservation reservation, String realName) {
        boolean open = task.getStatus().open();
        boolean hasReservation = task.getReservationId() != null;
        String reason = open ? null : "任务已是「" + task.getStatus().getText() + "」";
        return PatrolDTO.TaskVO.builder()
                .patrolId(task.getId())
                .floorId(task.getFloorId())
                .zoneId(task.getZoneId())
                .seatId(task.getSeatId())
                .seatNo(seat == null ? "座位已删除" : seat.getSeatNo())
                .reservationId(task.getReservationId())
                .userId(reservation == null ? null : reservation.getUserId())
                .userName(realName)
                .type(task.getType().name())
                .typeText(task.getType().getText())
                .level(task.getLevel().name())
                .levelText(task.getLevel().getText())
                .reason(task.getReason())
                .status(task.getStatus().name())
                .statusText(task.getStatus().getText())
                .evidenceItems(violationService.evidenceView(task.getEvidence()))
                .handlerId(task.getHandlerId())
                .handleNote(task.getHandleNote())
                .photoUrl(task.getPhotoUrl())
                .createTime(BizConstants.formatDateTime(task.getCreateTime()))
                .handleTime(BizConstants.formatDateTime(task.getHandleTime()))
                .canHandle(open)
                .handleDisabledReason(reason)
                .canRelease(open && hasReservation)
                .releaseDisabledReason(open && hasReservation ? null
                        : (open ? "这条任务没有关联预约，无座位可释放" : reason))
                .canViolate(open && hasReservation)
                .violateDisabledReason(open && hasReservation ? null
                        : (open ? "无预约占座请先人工登记，再由馆员当场裁定" : reason))
                .build();
    }

    private Seat requireSeat(Long seatId) {
        return seatRepository.findById(seatId == null ? -1L : seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "座位不存在"));
    }

    private Reservation relatedReservation(Long reservationId) {
        return reservationId == null ? null : reservationRepository.findById(reservationId).orElse(null);
    }

    /** 看板与处置回执里的「椅子上是谁」；无预约时没有身份可展示。 */
    private String reservationUserName(Long reservationId) {
        Reservation reservation = relatedReservation(reservationId);
        if (reservation == null) {
            return null;
        }
        return userRepository.findById(reservation.getUserId())
                .map(user -> user.getRealName() == null ? user.getUsername() : user.getRealName())
                .orElse(null);
    }

    /** 证据 JSON 还原成有序 map，便于把处置信息并进去而不是另写一份。 */
    private LinkedHashMap<String, Object> evidenceMap(String json) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if (!StringUtils.hasText(json)) {
            return map;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            root.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asText()));
        } catch (JsonProcessingException e) {
            map.put("raw", json);
        }
        return map;
    }

    private static int rank(String level) {
        return switch (level) {
            case "HIGH" -> 0;
            case "MID" -> 1;
            default -> 2;
        };
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

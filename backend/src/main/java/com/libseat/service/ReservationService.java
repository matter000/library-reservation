package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.common.PageResult;
import com.libseat.dto.ReservationDTO;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.entity.Seat;
import com.libseat.entity.User;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.UserRole;
import com.libseat.repository.LeaveRecordRepository;
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

    /**
     * 组队预约的统一管理提示。
     *
     * <p>★ 队伍是一个使用单元：个人入口若允许单独签到或取消，就会出现
     * 「组长还没签，成员先把自己点开」和「取消一个人、桌上其他人还占着」两种破口，
     * 而 {@code scanGroupAttendance} 的缺席判定整套都建立在「全队同进同出」上。
     */
    public static final String GROUP_MANAGED_HINT
            = "这是组队队伍的预约：签到由组长在「组队学习」完成（组长签到＝全队签到），"
            + "不再占用请退出队伍或由组长取消队伍";

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final SeatRepository seatRepository;

    private final com.libseat.repository.ZoneRepository zoneRepository;

    private final UserRepository userRepository;

    private final FloorService floorService;

    private final RuleConfigService ruleConfigService;

    private final OpenRuleService openRuleService;

    /** 只用于「暂离未归不可结束使用」这一道闸；不反向依赖 {@code LeaveService}，避免循环注入。 */
    private final LeaveRecordRepository leaveRecordRepository;

    /** 暂离按钮可用性由 LeaveService 单点判定（判定条件与登记暂离的校验链同源）。 */
    private final LeaveService leaveService;

    /** 信用闸门与主动结束奖励的唯一入口。 */
    private final CreditService creditService;

    /** 高峰判定（静态窗口按 scope 过滤 + 动态高峰状态），T-V2-18。 */
    private final PeakService peakService;

    /**
     * 候补顶位（只依赖仓储的写操作服务，T-V2-19）。
     *
     * <p>★ 刻意不注入 {@link WaitlistService}：那个用例服务要调本类的 {@code createReservation}
     * 完成确认下单，注入它就成了 ReservationService ⇄ WaitlistService 的构造器循环依赖，
     * 应用直接起不来。
     */
    private final WaitlistStateService waitlistStateService;

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
        return createReservation(userId, request, null);
    }

    /**
     * 提交预约，并可把它挂到一次组队下单上。
     *
     * <p>★ 组队刻意<b>复用这条校验链</b>而不是另写一套：七步里有五步（资格、开放日、时段窗口、
     * 时长上限、信用闸门与单日累计）对个人和队伍同样成立，复制一份迟早会有一边漏改。
     * 唯一的差别是 {@code group_required} 分区：它拒的是「个人散约」，整组下单正是它存在的理由，
     * 所以 {@code groupId != null} 时跳过该拒。
     *
     * @param userId  下单人
     * @param request 预约请求
     * @param groupId 所属队伍；null 表示个人预约
     * @return 创建结果
     */
    @Transactional
    public ReservationDTO.ReservationCreateVO createReservation(Long userId,
                                                                ReservationDTO.ReservationCreateRequest request,
                                                                Long groupId) {
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

        // ---------- 第 1b 步：分区资格与分区规则 ----------
        // 资格判定必须在服务端：前端隐藏入口只是体验层，直接打接口就能绕过
        com.libseat.entity.Zone seatZone = seat.getZoneId() == null ? null
                : zoneRepository.findById(seat.getZoneId()).orElse(null);
        UserRole role = userRepository.findById(userId)
                .map(com.libseat.entity.User::getRole).orElse(null);
        if (seatZone != null) {
            if (!ZoneService.containsRole(seatZone.getVisibleRoles(), role)
                    || !ZoneService.containsRole(seatZone.getEligibleRoles(), role)) {
                // 两种拒绝都指向同一事实「这个区不对你开放」；文案统一并点名分区，
                // 因为用户是拿着 seatId 直接打接口的，说「不可见」会让人对着看得见的座位发懵
                throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE,
                        seatZone.getName() + " 仅对 "
                                + seatZone.getEligibleRoles().replace(",", "、") + " 开放");
            }
            if (Boolean.TRUE.equals(seatZone.getGroupRequired()) && groupId == null) {
                // 只拒「个人散约」：整组下单就是这类分区存在的理由，不能再被同一条规则挡在门外
                throw new BusinessException(ErrorCode.GROUP_ONLY_ZONE,
                        seatZone.getName() + " 仅支持整组预约");
            }
        }

        // ---------- 第 2 步：日期在 [今天, 今天 + advance-days - 1] ----------
        int advanceDays = ruleConfigService.advanceDays();
        if (date.isBefore(today) || date.isAfter(today.plusDays(advanceDays - 1L))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能预约今天起 " + advanceDays + " 天内的座位");
        }

        // ---------- 第 2b 步：该日不开放（日型整体闭馆或日历临时闭馆）----------
        OpenRuleService.DayRule dayRule = openRuleService.resolve(date);
        if (dayRule.isClosed()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    date + " 闭馆，不接受预约" + (dayRule.getCalendarRemark() == null
                            ? "" : "（" + dayRule.getCalendarRemark() + "）"));
        }

        // ---------- 第 3 步：时段落在当日开放窗口内（30 分钟格，不跨天）----------
        // duration 是小时、startSlot 是 30 分钟格序号，必须先 slotsOf 换算再相加
        int endSlot = startSlot + BizConstants.slotsOf(duration);
        if (!BizConstants.validSlotIndex(startSlot) || endSlot > openWindowEndSlot(date)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约时段超出开放时间（"
                    + BizConstants.slotText(BizConstants.SLOT_INDEX_MIN) + "-"
                    + BizConstants.slotText(openWindowEndSlot(date)) + "）");
        }

        // ---------- 第 4 步：时长 ∈ 配置的可选值（默认 1/2/3/4 小时） ----------
        List<Integer> allowedDurations = ruleConfigService.allowedDurations();
        if (!allowedDurations.contains(duration)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "时长只能选择 "
                    + String.join("/", allowedDurations.stream().map(String::valueOf).toList()) + " 小时");
        }

        // ---------- 第 4b 步：分区时长上限 ----------
        // 分区上限以分钟表达，换算成小时再比较，避免「240 分钟 vs 4 小时」两套口径各写一遍
        if (seatZone != null && seatZone.getMaxDurationMin() != null) {
            int zoneMaxHours = Math.max(1, seatZone.getMaxDurationMin() / 60);
            if (duration > zoneMaxHours) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        seatZone.getName() + " 单次最长 " + seatZone.getMaxDurationMin()
                                + " 分钟，本次 " + duration + " 小时超限");
            }
        }

        // ---------- 第 4c 步：高峰调控（提前开放闸门 + 时长压缩，T-V2-18）----------
        // ★ 静态窗口必须按 scope 过滤、动态高峰也要算进来：只看「这一格有没有窗口」，
        //   会把 A 区的限流贴到 B 区头上，而馆员按占用率拉起来的高峰则完全没人拦。
        boolean targetPeak = false;
        if (seatZone != null) {
            peakService.requireOpen(seatZone, date, startSlot, now);
            targetPeak = peakService.isPeak(seatZone, date, startSlot, now);
            if (targetPeak) {
                Integer capMin = peakService.effectiveMaxDurationMin(seatZone, date, startSlot, role, now);
                if (capMin != null && duration * 60 > capMin) {
                    throw new BusinessException(ErrorCode.PEAK_BANNED,
                            "该时段为高峰，单次最长 " + capMin + " 分钟，本次 "
                                    + duration + " 小时超限（" + seatZone.getName() + "）");
                }
            }
        }

        // ---------- 第 4d 步：信用闸门（ACTIVE 处罚 → 4100/4101；分区单日上限 → 4098）----------
        // 顺序按 DESIGN §6.2：先看处罚，再看累计。单位注意：duration 是小时，
        // 分区上限是分钟，这里显式 *60，不混用格号。
        Integer dailyCapMin = seatZone == null ? null : seatZone.getDailyCapMin();
        long usedTodayMin = 0L;
        if (seatZone != null && dailyCapMin != null && dailyCapMin > 0) {
            usedTodayMin = (long) reservationRepository.sumSlotsOfUserZone(
                    userId, date, seatZone.getId(), OccupancyService.OCCUPYING_STATUSES)
                    * BizConstants.SLOT_MINUTES;
        }
        creditService.checkBookingGate(userId, date, startSlot, duration * 60,
                dailyCapMin, usedTodayMin, targetPeak);

        // ---------- 第 5 步：今天时起始时段未过期 ----------
        LocalDateTime start = BizConstants.slotStart(date, startSlot);
        if (!start.isAfter(now)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能预约已过去的时段");
        }

        // ---------- 第 6 步：用户无重叠有效预约 ----------
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
                .groupId(groupId)
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
        leaveService.enrichOwnerActions(userId, vos);
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
        ReservationDTO.ReservationVO vo = toVO(reservation, buildUsernameMap(userId), null, null);
        leaveService.enrichOwnerActions(userId, List.of(vo));
        return vo;
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

        if (reservation.getGroupId() != null) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, GROUP_MANAGED_HINT);
        }

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
        // 位置空出来就立刻顶位：取消的意义是「马上有人能约」，不是等下一轮扫描
        waitlistStateService.onReservationReleased(reservation, now);

        return ReservationDTO.ReservationActionVO.builder()
                .reservationId(reservation.getId())
                .status(ReservationStatus.CANCELLED.name())
                .cancelTime(BizConstants.formatDateTime(now))
                .build();
    }

    /**
     * 主动结束使用（DESIGN §5.4）：用不完的时间提前释放给他人。
     *
     * <p>与取消的区别在于「已经签到」，因此必须走独立入口：置 {@code COMPLETED}、物理删 slot 行、
     * 累计 {@code active_finish_streak}。暂离未返回时先返回才能结束，避免把「人还在外面」
     * 误记成一次主动结束。
     *
     * @param userId        当前用户 ID
     * @param reservationId 预约 ID
     * @return 操作结果
     */
    @Transactional
    public ReservationDTO.ReservationActionVO finish(Long userId, Long reservationId) {
        Reservation reservation = requireOwnReservation(userId, reservationId);
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "仅使用中的预约可提前结束");
        }
        boolean onLeave = leaveRecordRepository
                .findByReservationIdAndOpenFlag(reservationId, LeaveStatus.OPEN_FLAG)
                .isPresent();
        if (onLeave) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "暂离中请先点「我已返回」再结束使用");
        }

        LocalDateTime now = LocalDateTime.now();
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservationRepository.save(reservation);
        reservationSlotRepository.deleteByReservationId(reservation.getId());
        // §5.4 的「提前释放给他人」就落在这一步：整段 slot 已删，钩子只会唤醒起始格仍在未来的候补
        waitlistStateService.onReservationReleased(reservation, now);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        int streak = (user.getActiveFinishStreak() == null ? 0 : user.getActiveFinishStreak()) + 1;
        user.setActiveFinishStreak(streak);
        userRepository.save(user);
        // §5.4：每满 N 次给一次恢复加分并清零计数（加分与阶梯都在 CreditService 内完成）
        boolean bonusAwarded = creditService.awardStreakIfNeeded(userId);
        int score = creditService.scoreOf(userId);

        return ReservationDTO.ReservationActionVO.builder()
                .reservationId(reservation.getId())
                .status(ReservationStatus.COMPLETED.name())
                .finishTime(BizConstants.formatDateTime(now))
                .activeFinishStreak(user.getActiveFinishStreak())
                .streakBonusAwarded(bonusAwarded)
                .creditScore(score)
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

        if (reservation.getGroupId() != null) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, GROUP_MANAGED_HINT);
        }

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

        boolean groupManaged = reservation.getGroupId() != null;
        boolean canCancel = status == ReservationStatus.CONFIRMED && now.isBefore(start) && !groupManaged;
        boolean canCheckIn = status == ReservationStatus.CONFIRMED
                && !now.isBefore(openAt) && !now.isAfter(closeAt) && !groupManaged;

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
        if (groupManaged && status == ReservationStatus.CONFIRMED) {
            // ★ 不能给队伍成员显示个人签到窗口：那一按就绕过了「组长签到＝全队签到」
            windowText = "组队预约：请到「组队学习」由组长签到";
        }

        int duration = BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot());
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
                .groupId(reservation.getGroupId())
                .groupManagedHint(groupManaged ? GROUP_MANAGED_HINT : null)
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
            case "finished" -> List.of(ReservationStatus.COMPLETED, ReservationStatus.NO_SHOW,
                    ReservationStatus.VIOLATED);
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

    /**
     * 该日开放窗口的结束时段（开区间边界）。
     *
     * <p>窗口来自开放档案 / 日历而非 {@code BizConstants} 常量，
     * 因此周末与节假日会自然收窄，无需在多处重复写 22:00。
     *
     * @param date 预约日期
     * @return 结束时段的开区间序号
     */
    private int openWindowEndSlot(LocalDate date) {
        return openRuleService.endSlotOf(date);
    }
}

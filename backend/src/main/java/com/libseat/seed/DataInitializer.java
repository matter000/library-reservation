package com.libseat.seed;

import com.libseat.common.BizConstants;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.entity.Seat;
import com.libseat.entity.User;
import com.libseat.entity.Zone;
import com.libseat.enums.DiscussLevel;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.enums.UserRole;
import com.libseat.enums.ZoneType;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 种子数据初始化（幂等：仅当 t_user 为空时写入）。
 *
 * <p>用 Java 代码而非 data.sql：密码需 BCrypt 动态加密，座位需循环批量生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    /** 演示用固定随机种子，保证每次初始化分布一致。 */
    private static final long RANDOM_SEED = 20260211L;

    /** 分区行段归属：楼层编码 + 起止行号 → 分区编码。与 seedZones 的布局一一对应。 */
    private record ZoneRow(String floorCode, int rowFrom, int rowTo, String zoneCode) {
    }

    private static final List<ZoneRow> ZONE_ROW_MAP = List.of(
            new ZoneRow("1F", 1, 4, "1F-SILENT"),
            new ZoneRow("1F", 5, 5, "1F-SEMINAR"),
            new ZoneRow("1F", 6, 6, "1F-LOUNGE"),
            new ZoneRow("2F", 1, 3, "2F-E_READING"),
            new ZoneRow("2F", 4, 4, "2F-SILENT"),
            new ZoneRow("2F", 5, 5, "2F-TEACHER"),
            new ZoneRow("3F", 1, 2, "3F-EXAM"),
            new ZoneRow("3F", 3, 4, "3F-SILENT"));

    private final UserRepository userRepository;

    private final FloorRepository floorRepository;

    private final SeatRepository seatRepository;

    private final ZoneRepository zoneRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final PasswordEncoder passwordEncoder;

    private final com.libseat.service.CreditService creditService;

    private final com.libseat.repository.ViolationRepository violationRepository;

    private final org.springframework.core.env.Environment environment;

    /** 演示历史的可识别标记：证据里带它，重启时认得出"这条是种子造的"，不重复追加。 */
    private static final String DEMO_HISTORY_MARK = "DEMO-HISTORY";

    /**
     * 给三个学生演示账号补一段信用历史（T-V2-24 仍欠的"演示数据"项）。
     *
     * <p>★ 为什么写在 Java 而不是迁移里（DESIGN §14.3 / B9）：`t_violation` 的行要挂在用户身上，
     *   而用户由本类播种、跑在所有迁移**之后** —— 空库上"给 student1 造一条违规"的迁移
     *   面对的是还不存在的用户，写进去就是孤儿行或干脆 0 行。
     *
     * <p>★ 老演示库走的是上面那条"跳过初始化"分支，所以本方法**两条路径都调**，
     *   并靠证据里的 {@code DEMO_HISTORY_MARK} 自己判重，不依赖"表空不空"——
     *   重启 N 次也只有一条。
     *
     * <p>★ 一律走 {@code CreditService#applyViolation} 这个唯一写入口：它同笔写
     *   `t_violation` + `t_credit_log` 并更新 `t_user.credit_score`，
     *   因此 {@code sum(delta) == credit_score} 的对账不变式天然成立；
     *   自己拼三条 INSERT 破的正是它（缺证据还会被入口直接 4001 拒掉）。
     */
    private void seedDemoHistory() {
        // ★ 测试环境不补：用例自己用 newUser() 造隔离账号，演示历史只会让"某人的信用分是几分"
        //   这种断言凭空多扣 10 分。演示数据是给演示库看的，不是给夹具看的。
        if (java.util.Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            return;
        }
        for (String username : java.util.List.of("student1", "student2", "student3")) {
            userRepository.findByUsername(username).ifPresent(this::seedDemoHistoryFor);
        }
    }

    /** 为单个演示账号补一条带证据的爽约记录（已有标记则跳过）。 */
    private void seedDemoHistoryFor(User user) {
        boolean already = violationRepository.findByUserIdOrderByIdDesc(user.getId()).stream()
                .anyMatch(v -> v.getEvidence() != null && v.getEvidence().contains(DEMO_HISTORY_MARK));
        if (already) {
            return;
        }
        java.util.LinkedHashMap<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("marker", DEMO_HISTORY_MARK);
        evidence.put("scene", "演示数据：开馆巡查裁定");
        evidence.put("judgedAt", BizConstants.formatDateTime(
                java.time.LocalDateTime.now().minusDays(2)));
        evidence.put("seatNo", "A07");
        evidence.put("remark", "演示用历史爽约：预约后未在窗口内签到，按 PRD §12-20 扣 10 分；学生可在「信用与违规」页申诉");
        creditService.applyViolation(user.getId(), com.libseat.enums.ViolationType.NO_SHOW, null,
                creditService.buildEvidence(evidence), null, null);
        log.info("演示信用历史已补一条：user={}", user.getUsername());
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("种子数据已存在，跳过初始化（幂等）");
            seedDemoHistory();
            printDemoAccounts();
            return;
        }
        log.info("开始初始化种子数据 ...");

        List<User> users = seedUsers();
        List<Floor> floors = seedFloors();
        List<Seat> seats = seedSeats(floors);
        seedReservations(users, seats);
        seedDemoHistory();

        log.info("种子数据初始化完成：用户 {} 个，楼层 {} 个，座位 {} 个，预约 {} 条",
                userRepository.count(), floorRepository.count(), seatRepository.count(),
                reservationRepository.count());
        printDemoAccounts();
    }

    private List<User> seedUsers() {
        List<User> users = new ArrayList<>();
        users.add(User.builder()
                .username("admin")
                .passwordHash(passwordEncoder.encode("admin123"))
                .realName("系统管理员")
                .phone("13800000000")
                .role(UserRole.SYS_ADMIN)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("student1")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("张三")
                .studentNo("202401001")
                .phone("13812345671")
                .role(UserRole.STUDENT)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("student2")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("李四")
                .studentNo("202401002")
                .phone("13812345672")
                .role(UserRole.STUDENT)
                .noShowCount(1)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("student3")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("王五")
                .studentNo("202401003")
                .phone("13812345673")
                .role(UserRole.STUDENT)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("teacher1")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("陈老师")
                .studentNo("JS2024001")
                .phone("13812345674")
                .role(UserRole.TEACHER)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("librarian1")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("馆员小林")
                .studentNo("JS2024002")
                .phone("13800000001")
                .role(UserRole.LIBRARIAN)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        List<User> saved = userRepository.saveAll(users);
        // 种子账号同样要有 INIT 流水，否则「账本合计 == 信用分快照」的对账在演示库里恒不成立
        saved.forEach(user -> creditService.initAccount(user.getId()));
        return saved;
    }

    private List<Floor> seedFloors() {
        List<Floor> floors = new ArrayList<>();
        floors.add(Floor.builder()
                .name("一楼自习区").code("1F")
                .description("安静自习区，适合长时间学习")
                .sortOrder(1).status(FloorStatus.OPEN).build());
        floors.add(Floor.builder()
                .name("二楼阅览区").code("2F")
                .description("报刊与图书阅览区")
                .sortOrder(2).status(FloorStatus.OPEN).build());
        floors.add(Floor.builder()
                .name("三楼研讨区").code("3F")
                .description("小组研讨区，请保持低声交流")
                .sortOrder(3).status(FloorStatus.OPEN).build());
        return floorRepository.saveAll(floors);
    }

    private List<Seat> seedSeats(List<Floor> floors) {
        Map<String, Floor> floorByCode = new HashMap<>();
        for (Floor floor : floors) {
            floorByCode.put(floor.getCode(), floor);
        }
        List<Zone> zones = seedZones(floorByCode);

        // 分区按行划分：静音区占主要行列，研讨/教师专区等小而独立，便于演示可见性差异
        List<Seat> all = new ArrayList<>();
        all.addAll(generateSeats(floorByCode.get("1F"), "A", 1, 6, 10, zones));
        all.addAll(generateSeats(floorByCode.get("2F"), "B", 1, 5, 8, zones));
        all.addAll(generateSeats(floorByCode.get("3F"), "C", 1, 4, 6, zones));

        // 3 个维护中座位，用于演示灰色 UNAVAILABLE
        markMaintenance(all, floorByCode.get("1F").getId(), "A15");
        markMaintenance(all, floorByCode.get("2F").getId(), "B08");
        markMaintenance(all, floorByCode.get("3F").getId(), "C03");
        seatRepository.flush();
        return all;
    }

    private List<Zone> seedZones(Map<String, Floor> floorByCode) {
        List<Zone> zones = new ArrayList<>();
        Floor f1 = floorByCode.get("1F");
        Floor f2 = floorByCode.get("2F");
        Floor f3 = floorByCode.get("3F");

        zones.add(Zone.builder().floorId(f1.getId()).name("静音自习区").code("1F-SILENT")
                .zoneType(ZoneType.SILENT).maxDurationMin(240).advanceDays(3).dailyCapMin(360)
                .allowDiscuss(DiscussLevel.NO).allowLeave(true).leaveMaxMin(60).leaveDailyLimit(3)
                .allowGroup(false).groupRequired(false).sortOrder(1)
                .remark("全程静音，暂离需打卡，单日累计 6 小时").build());
        zones.add(Zone.builder().floorId(f1.getId()).name("研讨协作区").code("1F-SEMINAR")
                .zoneType(ZoneType.SEMINAR).maxDurationMin(180).advanceDays(3)
                .allowDiscuss(DiscussLevel.LOW).allowLeave(true).leaveMaxMin(60).leaveDailyLimit(3)
                .allowGroup(true).groupRequired(true).sortOrder(2)
                .remark("仅接受整组预约").build());
        zones.add(Zone.builder().floorId(f1.getId()).name("休闲阅览区").code("1F-LOUNGE")
                .zoneType(ZoneType.LOUNGE).maxDurationMin(60).advanceDays(1)
                .allowDiscuss(DiscussLevel.LOW).allowLeave(false).leaveMaxMin(0).leaveDailyLimit(0)
                .allowGroup(false).groupRequired(false).sortOrder(3)
                .remark("沙发区，不设暂离").build());

        zones.add(Zone.builder().floorId(f2.getId()).name("电子阅览区").code("2F-E_READING")
                .zoneType(ZoneType.E_READING).maxDurationMin(120).advanceDays(1)
                .allowDiscuss(DiscussLevel.NO).allowLeave(false).leaveMaxMin(0).leaveDailyLimit(0)
                .allowGroup(false).groupRequired(false).sortOrder(1)
                .remark("机房位，不支持暂离").build());
        zones.add(Zone.builder().floorId(f2.getId()).name("静音自习区").code("2F-SILENT")
                .zoneType(ZoneType.SILENT).maxDurationMin(240).advanceDays(3)
                .allowDiscuss(DiscussLevel.NO).allowLeave(true).leaveMaxMin(60).leaveDailyLimit(3)
                .allowGroup(false).groupRequired(false).sortOrder(2).build());
        // ★ 教师专区：对学生不可见，用于验证可见性过滤（T-V2-05 验收 2）
        zones.add(Zone.builder().floorId(f2.getId()).name("教师专区").code("2F-TEACHER")
                .zoneType(ZoneType.TEACHER_ONLY).maxDurationMin(360).advanceDays(3)
                .allowDiscuss(DiscussLevel.LOW).allowLeave(true).leaveMaxMin(60).leaveDailyLimit(3)
                .allowGroup(false).groupRequired(false)
                .visibleRoles("TEACHER,LIBRARIAN,SYS_ADMIN")
                .eligibleRoles("TEACHER,LIBRARIAN,SYS_ADMIN")
                .sortOrder(3).remark("学生面不可见").build());

        // ★ 这里的值必须与 V17 对 3F-EXAM 的处理一致：分区表**不设**比全馆口径更宽的日累计。
        //   迁移永远跑在 DataInitializer 之前，空库上 `UPDATE t_zone ... WHERE code='3F-EXAM'`
        //   面对的是 0 行 —— 指望迁移去纠正 Java 种出来的行，等于对新建库什么都没改（B9）。
        //   单次上限 480 仍保留：那是"本区口径可放宽"的正例，且只有考试周（全馆 480）才真约得下。
        zones.add(Zone.builder().floorId(f3.getId()).name("考研专座").code("3F-EXAM")
                .zoneType(ZoneType.EXAM_PREP).maxDurationMin(480).advanceDays(7)
                .allowDiscuss(DiscussLevel.NO).allowLeave(true).leaveMaxMin(120).leaveDailyLimit(5)
                .allowGroup(false).groupRequired(false).sortOrder(1)
                .remark("长时段，暂离上限放宽").build());
        zones.add(Zone.builder().floorId(f3.getId()).name("静音自习区").code("3F-SILENT")
                .zoneType(ZoneType.SILENT).maxDurationMin(240).advanceDays(3)
                .allowDiscuss(DiscussLevel.NO).allowLeave(true).leaveMaxMin(60).leaveDailyLimit(3)
                .allowGroup(false).groupRequired(false).sortOrder(2).build());

        return zoneRepository.saveAll(zones);
    }

    private List<Seat> generateSeats(Floor floor, String prefix, int startNo, int rows, int cols,
                                     List<Zone> zones) {
        int total = rows * cols;
        int lastNo = startNo + total - 1;
        int width = Math.max(2, String.valueOf(lastNo).length());
        String pattern = prefix + "%0" + width + "d";
        Map<String, Zone> zoneByCode = new HashMap<>();
        for (Zone zone : zones) {
            zoneByCode.put(zone.getCode(), zone);
        }
        List<Seat> seats = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int rowNo = i / cols + 1;
            Zone zone = resolveZone(floor.getCode(), rowNo, zoneByCode);
            seats.add(Seat.builder()
                    .floorId(floor.getId())
                    .seatNo(String.format(pattern, startNo + i))
                    .rowNo(rowNo)
                    .colNo(i % cols + 1)
                    .zone(zone == null ? null : zone.getName())
                    .zoneId(zone == null ? null : zone.getId())
                    .status(SeatStatus.AVAILABLE)
                    .build());
        }
        return new ArrayList<>(seatRepository.saveAll(seats));
    }

    private Zone resolveZone(String floorCode, int rowNo, Map<String, Zone> zoneByCode) {
        for (ZoneRow mapping : ZONE_ROW_MAP) {
            if (mapping.floorCode().equals(floorCode)
                    && rowNo >= mapping.rowFrom() && rowNo <= mapping.rowTo()) {
                return zoneByCode.get(mapping.zoneCode());
            }
        }
        return null;
    }

    private void markMaintenance(List<Seat> seats, Long floorId, String seatNo) {
        for (Seat seat : seats) {
            if (seat.getFloorId().equals(floorId) && seatNo.equals(seat.getSeatNo())) {
                seat.setStatus(SeatStatus.MAINTENANCE);
                seat.setRemark("设备检修中");
                seatRepository.save(seat);
                return;
            }
        }
    }

    private void seedReservations(List<User> users, List<Seat> seats) {
        Random random = new Random(RANDOM_SEED);
        LocalDate today = LocalDate.now();
        // 时段是 30 分钟一格，种子一律用 BizConstants 换算，不再写死小时字面量
        int currentSlot = BizConstants.slotOf(LocalDateTime.now().toLocalTime());
        int windowEnd = BizConstants.DEFAULT_SLOT_END;

        List<User> students = new ArrayList<>();
        for (User user : users) {
            if (user.getRole() == UserRole.STUDENT) {
                students.add(user);
            }
        }
        if (students.isEmpty() || seats.isEmpty()) {
            return;
        }

        Set<String> usedSlotKeys = new HashSet<>();
        List<Reservation> reservations = new ArrayList<>();
        List<ReservationSlot> slots = new ArrayList<>();

        // ---------- 过去 6 天：已完成 / 未签到 / 已取消（不写 slot） ----------
        for (int daysAgo = 6; daysAgo >= 1; daysAgo--) {
            LocalDate date = today.minusDays(daysAgo);
            int count = 4 + random.nextInt(2);
            for (int i = 0; i < count; i++) {
                int startSlot = randomStartSlot(random);
                int duration = 1 + random.nextInt(3);
                if (startSlot + BizConstants.slotsOf(duration) > windowEnd) {
                    duration = Math.max(1, (windowEnd - startSlot) / BizConstants.SLOTS_PER_HOUR);
                }
                int roll = random.nextInt(100);
                ReservationStatus status;
                if (roll < 70) {
                    status = ReservationStatus.COMPLETED;
                } else if (roll < 85) {
                    status = ReservationStatus.NO_SHOW;
                } else {
                    status = ReservationStatus.CANCELLED;
                }
                Seat seat = seats.get(random.nextInt(seats.size()));
                reservations.add(buildReservation(students.get(random.nextInt(students.size())), seat,
                        date, startSlot, duration, status, daysAgo, random, false, null));
            }
        }

        // ---------- 今天：已结束的上午时段 ----------
        if (currentSlot >= BizConstants.SLOT_INDEX_MIN + 4) {
            int upper = Math.min(currentSlot - 2, BizConstants.SLOT_INDEX_MAX - 2);
            for (int i = 0; i < 2; i++) {
                int startSlot = BizConstants.SLOT_INDEX_MIN
                        + random.nextInt(Math.max(1, upper - BizConstants.SLOT_INDEX_MIN));
                int duration = 1 + random.nextInt(2);
                if (startSlot + BizConstants.slotsOf(duration) > currentSlot) {
                    duration = Math.max(1,
                            (currentSlot - startSlot) / BizConstants.SLOTS_PER_HOUR);
                }
                ReservationStatus status = (i == 0) ? ReservationStatus.COMPLETED : ReservationStatus.NO_SHOW;
                Seat seat = seats.get(random.nextInt(seats.size()));
                reservations.add(buildReservation(students.get(random.nextInt(students.size())), seat,
                        today, startSlot, duration, status, 0, random, false, null));
            }
        }

        // ---------- 今天：正在使用中（CHECKED_IN，写 slot） ----------
        // 起始格取「当前格往前一小时」，配 2 小时时长，使该预约必然跨越当前时刻
        if (currentSlot >= BizConstants.SLOT_INDEX_MIN + 2
                && currentSlot <= BizConstants.DEFAULT_SLOT_END - 2) {
            int startSlot = currentSlot - 2;
            int duration = 2;
            for (int i = 0; i < 2; i++) {
                Seat seat = pickFreeSeat(random, seats, usedSlotKeys, today, startSlot, duration);
                if (seat == null) {
                    continue;
                }
                Reservation reservation = buildReservation(students.get(random.nextInt(students.size())), seat,
                        today, startSlot, duration, ReservationStatus.CHECKED_IN, 0, random, true, slots);
                reservation.setCheckInTime(BizConstants.slotStart(today, startSlot).plusMinutes(5));
                reservations.add(reservation);
            }
        }

        // ---------- 今天：未来的 CONFIRMED（写 slot） ----------
        if (currentSlot < BizConstants.SLOT_INDEX_MAX) {
            int latestStart = BizConstants.DEFAULT_SLOT_END - BizConstants.slotsOf(2);
            for (int i = 0; i < 3; i++) {
                int startSlot = Math.min(latestStart, currentSlot + 2 + i * 2);
                if (startSlot <= currentSlot || startSlot < BizConstants.SLOT_INDEX_MIN) {
                    continue;
                }
                int duration = 2;
                Seat seat = pickFreeSeat(random, seats, usedSlotKeys, today, startSlot, duration);
                if (seat == null) {
                    continue;
                }
                reservations.add(buildReservation(students.get(random.nextInt(students.size())), seat,
                        today, startSlot, duration, ReservationStatus.CONFIRMED, 0, random, true, slots));
            }
        }

        // ---------- 未来 2 天：CONFIRMED（写 slot） ----------
        for (int offset = 1; offset <= 2; offset++) {
            LocalDate date = today.plusDays(offset);
            for (int i = 0; i < 2; i++) {
                int startSlot = randomStartSlot(random);
                int duration = 2;
                Seat seat = pickFreeSeat(random, seats, usedSlotKeys, date, startSlot, duration);
                if (seat == null) {
                    continue;
                }
                reservations.add(buildReservation(students.get(random.nextInt(students.size())), seat,
                        date, startSlot, duration, ReservationStatus.CONFIRMED, -offset, random, true, slots));
            }
        }

        List<Reservation> saved = reservationRepository.saveAll(reservations);
        reservationRepository.flush();
        if (!slots.isEmpty()) {
            reservationSlotRepository.saveAll(slots);
            reservationSlotRepository.flush();
        }
        log.debug("种子预约 {} 条，占用明细 {} 行", saved.size(), slots.size());
    }

    /**
     * 随机起始时段（对齐整点）。
     *
     * <p>格长已是 30 分钟，但演示数据仍从整点起约，这样平面图与统计看起来与 V1 时代一致，
     * 便于对照验收 1「历史预约标签语义不变」。上限留出 3 小时余量，避免后续按时长截断。
     *
     * @param random 随机源
     * @return 起始时段序号
     */
    private int randomStartSlot(Random random) {
        int hours = (BizConstants.DEFAULT_SLOT_END - BizConstants.SLOT_INDEX_MIN) / BizConstants.SLOTS_PER_HOUR;
        int pick = random.nextInt(Math.max(1, hours - 3));
        return BizConstants.SLOT_INDEX_MIN + pick * BizConstants.SLOTS_PER_HOUR;
    }

    private Reservation buildReservation(User user, Seat seat, LocalDate date, int startSlot, int duration,
                                         ReservationStatus status, int daysAgo, Random random,
                                         boolean writeSlot, List<ReservationSlot> slotCollector) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createTime = (daysAgo > 0)
                ? now.minusDays(daysAgo).minusHours(random.nextInt(6))
                : now.minusMinutes(10 + random.nextInt(120));
        if (createTime.isAfter(now)) {
            createTime = now.minusMinutes(5);
        }
        Reservation reservation = Reservation.builder()
                .userId(user.getId())
                .seatId(seat.getId())
                .floorId(seat.getFloorId())
                .reserveDate(date)
                .startSlot(startSlot)
                .endSlot(startSlot + BizConstants.slotsOf(duration))
                .status(status)
                .createTime(createTime)
                .updateTime(createTime)
                .build();
        if (status == ReservationStatus.CANCELLED || status == ReservationStatus.NO_SHOW) {
            reservation.setCancelTime(BizConstants.slotStart(date, startSlot).minusMinutes(30));
        }
        if (status == ReservationStatus.COMPLETED || status == ReservationStatus.CHECKED_IN) {
            reservation.setCheckInTime(BizConstants.slotStart(date, startSlot).plusMinutes(5));
        }
        if (writeSlot && status.isOccupying() && slotCollector != null) {
            Reservation unsaved = reservation;
            for (int slot = startSlot; slot < startSlot + BizConstants.slotsOf(duration); slot++) {
                slotCollector.add(ReservationSlot.builder()
                        .reservation(unsaved)
                        .seatId(seat.getId())
                        .reserveDate(date)
                        .slotIndex(slot)
                        .build());
            }
        }
        return reservation;
    }

    private Seat pickFreeSeat(Random random, List<Seat> seats, Set<String> usedSlotKeys,
                              LocalDate date, int startSlot, int duration) {
        for (int attempt = 0; attempt < 30; attempt++) {
            Seat seat = seats.get(random.nextInt(seats.size()));
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                continue;
            }
            boolean free = true;
            for (int slot = startSlot; slot < startSlot + BizConstants.slotsOf(duration); slot++) {
                if (usedSlotKeys.contains(slotKey(seat.getId(), date, slot))) {
                    free = false;
                    break;
                }
            }
            if (free) {
                for (int slot = startSlot; slot < startSlot + BizConstants.slotsOf(duration); slot++) {
                    usedSlotKeys.add(slotKey(seat.getId(), date, slot));
                }
                return seat;
            }
        }
        return null;
    }

    private String slotKey(Long seatId, LocalDate date, int slot) {
        return seatId + "|" + date + "|" + slot;
    }

    private void printDemoAccounts() {
        log.info("=========================================================");
        log.info("  演示账号（密码明文）：");
        log.info("    系统管理员  admin / admin123       → /admin/statistics");
        log.info("    馆员        librarian1 / 123456    → /staff");
        log.info("    教师        teacher1 / 123456      → /floors");
        log.info("    学生        student1 / 123456（张三）→ /floors");
        log.info("    学生        student2 / 123456（李四）");
        log.info("    学生        student3 / 123456（王五）");
        log.info("=========================================================");
    }
}

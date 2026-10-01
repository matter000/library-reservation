package com.libseat.seed;

import com.libseat.common.BizConstants;
import com.libseat.entity.Floor;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.entity.Seat;
import com.libseat.entity.User;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.enums.UserRole;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.UserRepository;
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

    private final UserRepository userRepository;

    private final FloorRepository floorRepository;

    private final SeatRepository seatRepository;

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("种子数据已存在，跳过初始化（幂等）");
            printDemoAccounts();
            return;
        }
        log.info("开始初始化种子数据 ...");

        List<User> users = seedUsers();
        List<Floor> floors = seedFloors();
        List<Seat> seats = seedSeats(floors);
        seedReservations(users, seats);

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
                .phone("13812345671")
                .role(UserRole.STUDENT)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("student2")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("李四")
                .phone("13812345672")
                .role(UserRole.STUDENT)
                .noShowCount(1)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("student3")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("王五")
                .phone("13812345673")
                .role(UserRole.STUDENT)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("teacher1")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("陈老师")
                .phone("13812345674")
                .role(UserRole.TEACHER)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        users.add(User.builder()
                .username("librarian1")
                .passwordHash(passwordEncoder.encode("123456"))
                .realName("馆员小林")
                .phone("13800000001")
                .role(UserRole.LIBRARIAN)
                .noShowCount(0)
                .status("ACTIVE")
                .build());
        return userRepository.saveAll(users);
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
        List<Seat> all = new ArrayList<>();
        all.addAll(generateSeats(floorByCode.get("1F"), "A", 1, 6, 10, "A区"));
        all.addAll(generateSeats(floorByCode.get("2F"), "B", 1, 5, 8, "B区"));
        all.addAll(generateSeats(floorByCode.get("3F"), "C", 1, 4, 6, "C区"));

        // 3 个维护中座位，用于演示灰色 UNAVAILABLE
        markMaintenance(all, floorByCode.get("1F").getId(), "A15");
        markMaintenance(all, floorByCode.get("2F").getId(), "B08");
        markMaintenance(all, floorByCode.get("3F").getId(), "C03");
        seatRepository.flush();
        return all;
    }

    private List<Seat> generateSeats(Floor floor, String prefix, int startNo, int rows, int cols, String zone) {
        int total = rows * cols;
        int lastNo = startNo + total - 1;
        int width = Math.max(2, String.valueOf(lastNo).length());
        String pattern = prefix + "%0" + width + "d";
        List<Seat> seats = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            seats.add(Seat.builder()
                    .floorId(floor.getId())
                    .seatNo(String.format(pattern, startNo + i))
                    .rowNo(i / cols + 1)
                    .colNo(i % cols + 1)
                    .zone(zone)
                    .status(SeatStatus.AVAILABLE)
                    .build());
        }
        return new ArrayList<>(seatRepository.saveAll(seats));
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
        int currentHour = LocalDateTime.now().getHour();

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
                int startSlot = 8 + random.nextInt(12);
                int duration = 1 + random.nextInt(3);
                if (startSlot + duration > BizConstants.OPEN_HOUR_END) {
                    duration = BizConstants.OPEN_HOUR_END - startSlot;
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
        if (currentHour >= 10) {
            int upper = Math.min(currentHour - 1, 19);
            for (int i = 0; i < 2; i++) {
                int startSlot = 8 + random.nextInt(Math.max(1, upper - 8));
                int duration = 1 + random.nextInt(2);
                if (startSlot + duration > currentHour) {
                    duration = Math.max(1, currentHour - startSlot);
                }
                ReservationStatus status = (i == 0) ? ReservationStatus.COMPLETED : ReservationStatus.NO_SHOW;
                Seat seat = seats.get(random.nextInt(seats.size()));
                reservations.add(buildReservation(students.get(random.nextInt(students.size())), seat,
                        today, startSlot, duration, status, 0, random, false, null));
            }
        }

        // ---------- 今天：正在使用中（CHECKED_IN，写 slot） ----------
        if (currentHour >= 10 && currentHour <= 20) {
            int startSlot = currentHour - 1;
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
        if (currentHour <= 20) {
            for (int i = 0; i < 3; i++) {
                int startSlot = Math.min(BizConstants.SLOT_INDEX_MAX - 1, currentHour + 1 + i);
                if (startSlot <= currentHour || startSlot < BizConstants.SLOT_INDEX_MIN) {
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
                int startSlot = 9 + random.nextInt(10);
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
                .endSlot(startSlot + duration)
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
            for (int slot = startSlot; slot < startSlot + duration; slot++) {
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
            for (int slot = startSlot; slot < startSlot + duration; slot++) {
                if (usedSlotKeys.contains(slotKey(seat.getId(), date, slot))) {
                    free = false;
                    break;
                }
            }
            if (free) {
                for (int slot = startSlot; slot < startSlot + duration; slot++) {
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

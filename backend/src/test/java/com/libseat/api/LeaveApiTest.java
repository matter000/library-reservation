package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.LeaveRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S2 / T-V2-09：暂离登记与返回的校验链（DESIGN §5.2 七步）。
 *
 * <p>两点造数约定，都是为了让用例不随「当天几点跑」飘：
 * ① 预约用仓储直插 {@code CHECKED_IN} —— 真实签到窗口只有前后 15 分钟；
 * ② 需要「预约结束时刻足够远」的场景把结束日放到明天，需要「按剩余封顶」的场景
 *    把结束格设为当前格 +1，两种情况下与 now 的关系都恒定。
 *
 * <p>每日次数与「一条预约至多一条 OPEN」都是按人/按预约统计的，
 * 所以每个用例都用 {@code newUser()} 隔离，不依赖执行顺序。
 */
class LeaveApiTest extends ApiTestBase {

    private static final int PARAM_ERROR = 4001;
    private static final int NOT_FOUND = 4040;
    private static final int STATUS_NOT_ALLOWED = 4093;
    private static final int LEAVE_NOT_ALLOWED = 4095;
    private static final int LEAVE_LIMIT_EXCEEDED = 4096;
    private static final int LEAVE_ALREADY_OPEN = 4097;

    /** 开放窗口末尾格（22:00）：合法预约的 endSlot 不会越过它。 */
    private static final int DAY_LAST_SLOT = BizConstants.SLOT_INDEX_MAX + 1;

    @Autowired
    private LeaveRecordRepository leaveRecordRepository;

    /**
     * ★ 本类每个用例都以「此刻这一格」搭现场，而库区开放窗口是 08:00-22:00（合法格号 16~43）。
     *
     * <p>22:00 一过，{@code slotOf(now)} 变成 44：平面图查询会把它夹回 43，于是出现
     * 「明明签到了却显示空闲」；两张 fixture 又都往 44 格写占用行，直接撞
     * {@code uk_slot_seat_date_idx}。那是时刻的问题而不是实现的问题 —— 越界一律跳过，
     * 不去伪造一个产品里不可能存在的数据。
     */
    @BeforeEach
    void skipOutsideOpenWindow() {
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        assumeTrue(nowSlot >= BizConstants.SLOT_INDEX_MIN && nowSlot <= BizConstants.SLOT_INDEX_MAX,
                "当前时刻 " + LocalTime.now() + " 已不在开放时段内（slotOf=" + nowSlot
                        + "，合法区间 " + BizConstants.SLOT_INDEX_MIN + "~" + BizConstants.SLOT_INDEX_MAX
                        + "），暂离用例都以「此刻这一格」搭现场，跳过");
    }

    /** 直插一条「使用中」预约，并同步写占用明细（平面图只认 t_reservation_slot）。 */
    private Reservation checkedIn(Long userId, long seatId, long floorId, LocalDate date,
                                  int startSlot, int slotCount) {
        // ★ 预约不出当天：产品里开放窗口是 08:00-22:00，任何合法预约的 endSlot 都 ≤ 44。
        //   fixture 若跨过当天末尾（如 21:30 起连 6 格），endSlot 会落到 49，
        //   而 BizConstants.slotEnd 的契约要求「跨 24:00 的边界由调用方保证」——
        //   于是「预约结束时刻」绕回<b>当天</b> 00:30，暂离剩余分钟变成负一千多，
        //   看起来是实现错了，其实是现场搭出了产品不可能产生的数据。
        int safeCount = Math.max(1, Math.min(slotCount, DAY_LAST_SLOT - startSlot));
        Reservation reservation = Reservation.builder()
                .userId(userId)
                .seatId(seatId)
                .floorId(floorId)
                .reserveDate(date)
                .startSlot(startSlot)
                .endSlot(startSlot + safeCount)
                .status(ReservationStatus.CHECKED_IN)
                .checkInTime(BizConstants.slotStart(date, startSlot))
                .build();
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        for (int i = 0; i < safeCount; i++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(saved)
                    .seatId(seatId)
                    .reserveDate(date)
                    .slotIndex(startSlot + i)
                    .build());
        }
        return saved;
    }

    /** 取某分区内在 [startSlot, startSlot+slotCount) 全时段都空闲的第 index 个座位。 */
    private long freeSeatOfZone(long floorId, String zoneCode, LocalDate date,
                               int startSlot, int slotCount, int index) throws Exception {
        JsonNode zones = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floorId))
                .header("Authorization", bearer(admin))));
        long zoneId = -1;
        for (JsonNode node : zones) {
            if (zoneCode.equals(node.path("code").asText())) {
                zoneId = node.path("zoneId").asLong();
            }
        }
        assertTrue(zoneId > 0, "分区列表中应含 " + zoneCode);

        // ★ 必须逐格取交集：种子数据已把今天到 +6 天的部分格子占了，
        //   只按「起始格空闲」造数会在写 slot 行时撞 uk_slot_seat_date_idx。
        Set<Long> candidates = null;
        for (int i = 0; i < slotCount; i++) {
            JsonNode map = data(exec(get("/api/seats/map")
                    .param("floorId", String.valueOf(floorId))
                    .param("zoneId", String.valueOf(zoneId))
                    .param("date", date.toString())
                    .param("slot", String.valueOf(startSlot + i))
                    .header("Authorization", bearer(admin))));
            Set<Long> free = new LinkedHashSet<>();
            for (JsonNode seat : map.path("seats")) {
                if ("FREE".equals(seat.path("displayStatus").asText())) {
                    free.add(seat.path("seatId").asLong());
                }
            }
            if (candidates == null) {
                candidates = free;
            } else {
                candidates.retainAll(free);
            }
        }
        assertNotNull(candidates, "交集初始化失败");
        assertTrue(candidates.size() > index,
                zoneCode + " 在 " + date + " 时段 " + startSlot + ".." + (startSlot + slotCount)
                        + " 需要至少 " + (index + 1) + " 个全空闲座位，实际 " + candidates.size());
        return new ArrayList<>(candidates).get(index);
    }

    private long userId(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private MvcResult start(String token, long reservationId, String reasonCode, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reservationId", reservationId);
        body.put("reasonCode", reasonCode);
        if (note != null) {
            body.put("note", note);
        }
        return exec(post("/api/leaves")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(token)));
    }

    private JsonNode startOk(String token, long reservationId, String reasonCode, String note) throws Exception {
        MvcResult result = start(token, reservationId, reasonCode, note);
        assertEquals(0, code(result), reasonCode + " 登记应成功，实际：" + body(result));
        return data(result);
    }

    private MvcResult returnBack(String token, long leaveId) throws Exception {
        return exec(post("/api/leaves/" + leaveId + "/return")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of()))
                .header("Authorization", bearer(token)));
    }

    private void returnOpenLeave(String token, long reservationId) throws Exception {
        LeaveRecord open = leaveRecordRepository
                .findByReservationIdAndOpenFlag(reservationId, LeaveStatus.OPEN_FLAG).orElseThrow();
        MvcResult result = returnBack(token, open.getId());
        assertEquals(0, code(result), "返回应成功：" + body(result));
    }

    private MvcResult finish(String token, long reservationId) throws Exception {
        return exec(post("/api/reservations/" + reservationId + "/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of()))
                .header("Authorization", bearer(token)));
    }

    private int slotRowCount(long reservationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_reservation_slot WHERE reservation_id = ?",
                Integer.class, reservationId);
        return n == null ? 0 : n;
    }

    private int openLeaveCount(long reservationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(1) FROM t_leave_record WHERE reservation_id = ? AND open_flag = 1",
                Integer.class, reservationId);
        return n == null ? 0 : n;
    }

    /** 某日某格的 HH:mm 标签（与后端同一换算）。 */
    private String hm(LocalDate date, int slot) {
        LocalDateTime time = BizConstants.slotEnd(date, slot);
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    @Test
    @DisplayName("【验收0】原因字典 6 项，仅 CLASS/OTHER 要求填事由")
    void reasonDictionaryExposed() throws Exception {
        JsonNode list = data(exec(get("/api/leaves/reasons").header("Authorization", bearer(u1))));
        assertEquals(6, list.size(), "种子应给出 6 个原因，实际 " + list.size());
        List<String> needNote = new ArrayList<>();
        for (JsonNode node : list) {
            if (node.path("needNote").asBoolean()) {
                needNote.add(node.path("code").asText());
            }
        }
        assertEquals(List.of("CLASS", "OTHER"), needNote, "只有上课与其他必须写事由");
    }

    @Test
    @DisplayName("【验收1】planned_end = min(原因上限, 分区上限, 预约结束)，剩余不足按剩余封顶而非报错")
    void plannedEndIsCapped() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        int nowSlot = BizConstants.slotOf(LocalTime.now());

        // 考研专座 leave_max_min=120；原因 MEAL=60 → 原因更紧，取 60
        // 结束时刻刻意放在明天：无论几点跑，预约结束都不会先被触发
        Reservation r1 = checkedIn(uid, freeSeatOfZone(floor3, "3F-EXAM", tomorrow, 16, 8, 0),
                floor3, tomorrow, 16, 8);
        JsonNode vo1 = startOk(me, r1.getId(), "MEAL", null);
        assertEquals("OPEN", vo1.path("status").asText());
        assertEquals(false, vo1.path("returned").asBoolean());
        assertTrue(vo1.path("remainMin").asInt() >= 58 && vo1.path("remainMin").asInt() <= 60,
                "就餐上限 60 分钟应生效，实际 remainMin=" + vo1.path("remainMin").asInt());

        // 更短的原因（取物 15）→ 15 生效
        returnOpenLeave(me, r1.getId());
        JsonNode vo2 = startOk(me, r1.getId(), "PICKUP", null);
        assertTrue(vo2.path("remainMin").asInt() >= 13 && vo2.path("remainMin").asInt() <= 15,
                "取物上限 15 分钟应生效，实际 remainMin=" + vo2.path("remainMin").asInt());

        // ★ 预约只剩 1 格 → 按剩余封顶，且必须成功而不是报错
        returnOpenLeave(me, r1.getId());
        Reservation r2 = checkedIn(uid, freeSeatOfZone(floor3, "3F-EXAM", today, nowSlot, 1, 0),
                floor3, today, nowSlot, 1);
        JsonNode vo3 = startOk(me, r2.getId(), "MEAL", null);
        assertEquals(hm(today, r2.getEndSlot()), vo3.path("plannedEndTime").asText(),
                "剩余不足时应等于预约结束时刻 " + hm(today, r2.getEndSlot()));
        assertTrue(vo3.path("remainMin").asInt() <= 30, "封顶后剩余不应超过 30 分钟");
    }

    @Test
    @DisplayName("【验收2】分区不支持 4095 / 已在暂离 4097 / CLASS 缺事由 4001 / 当日第 4 次 4096")
    void validationChainErrors() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());

        // 第 2 步：2F 电子阅览区 allow_leave=false → 4095（且要先于原因校验命中）
        Reservation eReading = checkedIn(uid, freeSeatOfZone(floor2, "2F-E_READING", tomorrowOf(today), 16, 4, 0),
                floor2, tomorrowOf(today), 16, 4);
        MvcResult notAllowed = start(me, eReading.getId(), "MEAL", null);
        assertEquals(LEAVE_NOT_ALLOWED, code(notAllowed),
                "电子阅览区应拒绝暂离，实际：" + body(notAllowed));

        // 每次都重新取「当前全空闲」的第 0 座：上一条造数已把上一个座位标脏，天然错开
        Reservation r1 = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", tomorrowOf(today), 16, 8, 0),
                floor1, tomorrowOf(today), 16, 8);
        assertEquals(0, code(start(me, r1.getId(), "MEAL", null)), "静音区应可暂离");

        // 第 3 步：同一条预约再次登记 → 4097
        MvcResult again = start(me, r1.getId(), "PHONE", null);
        assertEquals(LEAVE_ALREADY_OPEN, code(again), "已有 OPEN 记录应 4097，实际：" + body(again));

        // 第 5 步：CLASS 不填事由 → 4001。必须先返回上一条，否则命中第 3 步的 4097
        returnOpenLeave(me, r1.getId());
        Reservation r2 = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", tomorrowOf(today), 16, 4, 0),
                floor1, tomorrowOf(today), 16, 4);
        MvcResult needNote = start(me, r2.getId(), "CLASS", null);
        assertEquals(PARAM_ERROR, code(needNote), "上课原因不填事由应 4001，实际：" + body(needNote));
        startOk(me, r2.getId(), "CLASS", "去上第三节课");

        // 第 4 步：静音区每日 3 次。已用 2 次 → 第 3 次仍可，第 4 次 4096
        returnOpenLeave(me, r2.getId());
        Reservation r3 = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", tomorrowOf(today), 16, 4, 0),
                floor1, tomorrowOf(today), 16, 4);
        startOk(me, r3.getId(), "REST", null);
        returnOpenLeave(me, r3.getId());

        Reservation r4 = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 2, 0),
                floor1, today, nowSlot, 2);
        MvcResult exceeded = start(me, r4.getId(), "MEAL", null);
        assertEquals(LEAVE_LIMIT_EXCEEDED, code(exceeded),
                "静音区每日 3 次，第 4 次应 4096，实际：" + body(exceeded));
    }

    private LocalDate tomorrowOf(LocalDate today) {
        return today.plusDays(1);
    }

    @Test
    @DisplayName("【验收3】我已返回 → RETURNED + open_flag 归 NULL + 座位仍占用 + 结束时刻不顺延")
    void returnBackKeepsOriginalEnd() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long seat = freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 6, 0);
        Reservation reservation = checkedIn(uid, seat, floor1, today, nowSlot, 6);

        JsonNode started = startOk(me, reservation.getId(), "MEAL", null);
        long leaveId = started.path("leaveId").asLong();
        String endBefore = started.path("reservationEndTime").asText();
        assertEquals("暂离中", started.path("statusText").asText());
        assertEquals("ON_LEAVE", displayStatus(floor1, seat, today.toString(), nowSlot),
                "T-V2-10 后暂离期间派生为 ON_LEAVE（仍算占用，见 mapDerivesOnLeave 的余量断言）");

        MvcResult returnResult = returnBack(me, leaveId);
        assertEquals(0, code(returnResult), "返回应成功：" + body(returnResult));
        JsonNode returned = data(returnResult);
        assertEquals("RETURNED", returned.path("status").asText());
        assertEquals(true, returned.path("returned").asBoolean());
        assertTrue(returned.path("actualReturnTime").asText().length() == 5,
                "实际返回时刻应为 HH:mm，实际 " + returned.path("actualReturnTime").asText());
        assertEquals(endBefore, returned.path("reservationEndTime").asText(),
                "★ 剩余使用时间不顺延：预约结束时刻必须一字不变");
        assertEquals("IN_USE", displayStatus(floor1, seat, today.toString(), nowSlot),
                "返回后座位仍属该预约，没有提前释放");

        LeaveRecord record = leaveRecordRepository.findById(leaveId).orElseThrow();
        assertNull(record.getOpenFlag(),
                "★ 结束后 open_flag 必须置回 NULL，否则该预约再也开不了第二条暂离");
        assertEquals(LeaveStatus.RETURNED, record.getStatus());
        assertEquals(0, openLeaveCount(reservation.getId()));

        // 名额已释放：同一条预约可以再登记
        startOk(me, reservation.getId(), "PHONE", null);

        // 已结束的记录重复返回 → 4093
        MvcResult again = returnBack(me, record.getId());
        assertEquals(STATUS_NOT_ALLOWED, code(again),
                "重复返回应 4093，实际：" + body(again));
    }

    @Test
    @DisplayName("【验收4】并发登记同预约 → 唯一索引只放过一条，失败方拿到 4097")
    void concurrentStartSingleWinner() throws Exception {
        String me = newUser();
        long uid = userId(me);
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long seat = freeSeatOfZone(floor1, "1F-SILENT", LocalDate.now(), nowSlot, 6, 0);
        Reservation reservation = checkedIn(uid, seat, floor1, LocalDate.now(), nowSlot, 6);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Callable<Integer> task = () -> code(start(me, reservation.getId(), "MEAL", null));
                futures.add(pool.submit(task));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get());
            }
            long success = codes.stream().filter(c -> c == 0).count();
            assertEquals(1, success, "★ 只允许一条成功，实际返回：" + codes);
            for (int c : codes) {
                assertTrue(c == 0 || c == LEAVE_ALREADY_OPEN,
                        "失败方必须明确报 4097（而不是 5000 或静默成功），实际：" + codes);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, openLeaveCount(reservation.getId()), "库里 OPEN 记录必须恰好一条");

        // ★ 单独证明「索引才是兜底」：绕过服务直插第二条 OPEN 也必须被数据库拒掉，
        //   否则并发场景里只是先查后插侥幸拦住的，换个调用顺序就会漏。
        int before = openLeaveCount(reservation.getId());
        try {
            leaveRecordRepository.saveAndFlush(LeaveRecord.builder()
                    .reservationId(reservation.getId()).userId(uid).seatId(seat)
                    .reasonCode("MEAL")
                    .startTime(LocalDateTime.now()).plannedEndTime(LocalDateTime.now().plusMinutes(30))
                    .status(LeaveStatus.OPEN).openFlag(LeaveStatus.OPEN_FLAG)
                    .build());
            throw new AssertionError("第二条 OPEN 暂离本应被 uk_leave_open 拦下");
        } catch (DataIntegrityViolationException expected) {
            assertEquals(before, openLeaveCount(reservation.getId()),
                    "被拦下后库里仍应只有一条 OPEN");
        }

        // 历史态（open_flag=NULL）可以无限条：结束后再来一条不应撞索引
        returnOpenLeave(me, reservation.getId());
        startOk(me, reservation.getId(), "MEAL", null);
        assertEquals(1, openLeaveCount(reservation.getId()), "返回后重新占额，仍只有一条 OPEN");
    }

    @Test
    @DisplayName("【T-V2-10 验收1】暂离期间平面图该座 ON_LEAVE 且带预计返回时刻，返回后回 IN_USE")
    void mapDerivesOnLeave() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long seat = freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 6, 0);
        Reservation reservation = checkedIn(uid, seat, floor1, today, nowSlot, 6);
        String date = today.toString();

        assertEquals("IN_USE", displayStatus(floor1, seat, date, nowSlot));
        JsonNode before = seatRow(floor1, date, nowSlot, seat);
        assertEquals(0, before.path("stats").path("onLeave").asInt(), "未暂离时 onLeave 计数应为 0");
        int freeBefore = before.path("stats").path("free").asInt();

        JsonNode started = startOk(me, reservation.getId(), "MEAL", null);
        JsonNode after = seatRow(floor1, date, nowSlot, seat);
        JsonNode row = findSeatRow(after.path("seats"), seat);
        assertEquals("ON_LEAVE", row.path("displayStatus").asText(),
                "暂离期间平面图必须是暂离态，而不是继续使用中");
        assertEquals(started.path("plannedEndTime").asText(), row.path("expectedReturnAt").asText(),
                "文案数据源：预计返回时刻由后端给出（PRD §13.3 文案优先于颜色）");
        assertEquals(1, after.path("stats").path("onLeave").asInt(), "onLeave 计数要单独可见");
        assertEquals(freeBefore, after.path("stats").path("free").asInt(),
                "★ 暂离仍占座：空闲数不得因暂离而变多");
        assertEquals(0, zoneFreeDelta(floor1, date, nowSlot), "分区卡也必须把暂离算作占用");

        returnOpenLeave(me, reservation.getId());
        JsonNode back = seatRow(floor1, date, nowSlot, seat);
        assertEquals("IN_USE", findSeatRow(back.path("seats"), seat).path("displayStatus").asText(),
                "返回后回到使用中");
        assertEquals(0, back.path("stats").path("onLeave").asInt());
    }

    private JsonNode seatRow(long floorId, String date, int slot, long seatId) throws Exception {
        JsonNode map = seatRow(floorId, date, slot);
        findSeatRow(map.path("seats"), seatId);   // 顺带断言「该座确实出现在这一层的平面图里」
        return map;
    }

    private JsonNode seatRow(long floorId, String date, int slot) throws Exception {
        return data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1))));
    }

    private JsonNode findSeatRow(JsonNode seats, long seatId) {
        for (JsonNode node : seats) {
            if (node.path("seatId").asLong() == seatId) {
                return node;
            }
        }
        throw new AssertionError("平面图响应里找不到座位 " + seatId);
    }

    /** 分区卡的「空闲之和」与整层平面图 free 的差值，必须恒为 0。 */
    private long zoneFreeDelta(long floorId, String date, int slot) throws Exception {
        JsonNode zones = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floorId))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(u1))));
        long sum = 0;
        for (JsonNode zone : zones) {
            sum += zone.path("freeSeats").asLong();
        }
        JsonNode whole = seatRow(floorId, date, slot);
        return whole.path("stats").path("free").asLong() - sum;
    }

    @Test
    @DisplayName("【T-V2-10 验收2】平面图响应不含任何占用人身份，暂离也只给时刻")
    void mapLeaksNoOccupantIdentity() throws Exception {
        String me = newUser();
        long uid = userId(me);
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long seat = freeSeatOfZone(floor1, "1F-SILENT", LocalDate.now(), nowSlot, 6, 0);
        Reservation reservation = checkedIn(uid, seat, floor1, LocalDate.now(), nowSlot, 6);
        startOk(me, reservation.getId(), "MEAL", null);

        JsonNode map = seatRow(floor1, LocalDate.now().toString(), nowSlot);
        String raw = map.toString().toLowerCase();
        for (String forbidden : List.of("username", "realname", "phonenumber", "\"phone\"", "studentno")) {
            assertFalse(raw.contains(forbidden), "平面图不应出现字段 " + forbidden);
        }
        JsonNode row = findSeatRow(map.path("seats"), seat);
        assertTrue(row.hasNonNull("expectedReturnAt"), "暂离行必须带预计返回时刻字段");
        assertTrue(row.path("expectedReturnAt").asText().matches("\\d{2}:\\d{2}"),
                "只给时刻：" + row.path("expectedReturnAt").asText());
    }

    @Test
    @DisplayName("【T-V2-10 验收4】我的预约按钮可用性后端算，置灰必带原因")
    void ownerActionFlags() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long silent = freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 6, 0);
        Reservation reservation = checkedIn(uid, silent, floor1, today, nowSlot, 6);

        JsonNode vo = myReservation(me, reservation.getId(), "inProgress");
        assertEquals(true, vo.path("canLeave").asBoolean(), "使用中且未暂离 → 可暂离");
        assertEquals(false, vo.path("canReturn").asBoolean(), "没在暂离 → 不可返回");
        assertEquals(true, vo.path("canFinish").asBoolean(), "使用中且未暂离 → 可提前结束");
        assertTrue(vo.path("leaveDisabledReason").isNull() || vo.path("leaveDisabledReason").asText().isEmpty(),
                "可用时不应给原因");

        JsonNode started = startOk(me, reservation.getId(), "MEAL", null);
        JsonNode onLeave = myReservation(me, reservation.getId(), "inProgress");
        assertEquals(false, onLeave.path("canLeave").asBoolean(), "已在暂离中不可再登记");
        assertEquals(true, onLeave.path("canReturn").asBoolean());
        assertEquals(false, onLeave.path("canFinish").asBoolean(), "暂离中不可提前结束");
        assertEquals("当前已在暂离中，请先返回", onLeave.path("leaveDisabledReason").asText(),
                "★ 置灰必须给出具体原因");
        assertEquals(started.path("plannedEndTime").asText(), onLeave.path("expectedReturnAt").asText());
        assertTrue(onLeave.path("leaveRemainMin").asInt() > 0, "剩余分钟应为正");
        long activeLeaveId = onLeave.path("activeLeaveId").asLong();
        assertTrue(activeLeaveId > 0, "★「我已返回」只需要 leaveId，必须由后端一并给出，不能让前端再查");
        assertEquals(0, code(returnBack(me, activeLeaveId)), "用 VO 给的 activeLeaveId 应能直接返回");
        JsonNode returned = myReservation(me, reservation.getId(), "inProgress");
        assertEquals(true, returned.path("canLeave").asBoolean(), "返回后应重新可暂离");
        assertEquals(false, returned.path("canReturn").asBoolean());

        // 不支持暂离的分区：给分区名 + 明确文案
        long eSeat = freeSeatOfZone(floor2, "2F-E_READING", today.plusDays(1), 16, 4, 0);
        Reservation eReading = checkedIn(uid, eSeat, floor2, today.plusDays(1), 16, 4);
        JsonNode notAllowed = myReservation(me, eReading.getId(), "inProgress");
        assertEquals(false, notAllowed.path("canLeave").asBoolean());
        assertTrue(notAllowed.path("leaveDisabledReason").asText().contains("不支持暂离"),
                "应说明是分区策略，实际：" + notAllowed.path("leaveDisabledReason").asText());

        // 未签到的预约：按钮不给亮，理由说清楚
        Reservation confirmed = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(uid).seatId(silent).floorId(floor1).reserveDate(today.plusDays(2))
                .startSlot(16).endSlot(20).status(ReservationStatus.CONFIRMED).build());
        JsonNode notCheckedIn = myReservation(me, confirmed.getId(), "upcoming");
        assertEquals(false, notCheckedIn.path("canLeave").asBoolean());
        assertEquals("签到之后才能登记暂离", notCheckedIn.path("leaveDisabledReason").asText());
        assertEquals(false, notCheckedIn.path("canFinish").asBoolean(), "未签到谈不上提前结束");

        // 用完次数：静音区每日 3 次。本用例此前已用 1 次（上面那条 MEAL），再补 2 次即满额，
        // 第 4 次的置灰理由要落到「次数」上。
        // （造数放明天的 16/18 格：今天的格子可能被种子数据占掉，且 nowSlot 附近会越界）
        for (int i = 0; i < 2; i++) {
            int slot = 16 + i * 2;
            long extra = freeSeatOfZone(floor1, "1F-SILENT", today.plusDays(1), slot, 2, 0);
            Reservation r = checkedIn(uid, extra, floor1, today.plusDays(1), slot, 2);
            startOk(me, r.getId(), "PHONE", null);
            returnOpenLeave(me, r.getId());
        }
        long lastSeat = freeSeatOfZone(floor1, "1F-SILENT", today.plusDays(1), 22, 2, 0);
        Reservation last = checkedIn(uid, lastSeat, floor1, today.plusDays(1), 22, 2);
        JsonNode exhausted = myReservation(me, last.getId(), "inProgress");
        assertEquals(false, exhausted.path("canLeave").asBoolean());
        assertTrue(exhausted.path("leaveDisabledReason").asText().contains("今日暂离次数已用完"),
                "实际：" + exhausted.path("leaveDisabledReason").asText());
    }

    /** 从「我的预约」某个分组里取一条 VO（分组是状态过滤，取错组就找不到）。 */
    private JsonNode myReservation(String token, long reservationId, String tab) throws Exception {
        JsonNode page = data(exec(get("/api/reservations/my")
                .param("tab", tab)
                .param("page", "1").param("size", "50")
                .header("Authorization", bearer(token))));
        for (JsonNode node : page.path("list")) {
            if (node.path("reservationId").asLong() == reservationId) {
                return node;
            }
        }
        throw new AssertionError("我的预约 " + tab + " 分组里找不到 " + reservationId);
    }

    @Test
    @DisplayName("【验收5】未签到/已结束后不可暂离；暂离中不可提前结束使用")
    void finishedReservationRejectsLeave() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());
        long seat = freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 6, 0);

        // 第 1 步：CONFIRMED（未签到）→ 4093
        Reservation confirmed = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(uid).seatId(seat).floorId(floor1).reserveDate(today.plusDays(1))
                .startSlot(16).endSlot(20).status(ReservationStatus.CONFIRMED).build());
        MvcResult notCheckedIn = start(me, confirmed.getId(), "MEAL", null);
        assertEquals(STATUS_NOT_ALLOWED, code(notCheckedIn), "未签到应 4093，实际：" + body(notCheckedIn));

        // 主动结束使用 → COMPLETED + slot 物理删除 + 计数累加
        Reservation active = checkedIn(uid, seat, floor1, today, nowSlot, 6);
        MvcResult finished = finish(me, active.getId());
        assertEquals(0, code(finished), "主动结束应成功：" + body(finished));
        assertEquals("COMPLETED", data(finished).path("status").asText());
        assertEquals(1, data(finished).path("activeFinishStreak").asInt(), "连续主动结束次数要累加");
        assertEquals(0, slotRowCount(active.getId()), "结束后 slot 行必须物理删除，座位立刻让给他人");

        MvcResult afterFinish = start(me, active.getId(), "MEAL", null);
        assertEquals(STATUS_NOT_ALLOWED, code(afterFinish),
                "已结束的预约不可再暂离，实际：" + body(afterFinish));

        // 暂离中 → 先返回才能结束
        Reservation another = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 6, 0),
                floor1, today, nowSlot, 6);
        startOk(me, another.getId(), "MEAL", null);
        MvcResult blocked = finish(me, another.getId());
        assertEquals(STATUS_NOT_ALLOWED, code(blocked),
                "暂离中应先返回，实际：" + body(blocked));
        assertEquals(another.getEndSlot() - another.getStartSlot(), slotRowCount(another.getId()),
                "★ 被拒的结束一条 slot 行都不能删；条数按预约本身的格数算，而不是写死一个数字"
                        + "（fixture 的格数会因开放窗口末尾而收缩，写死就成了测现场搭建）");

        // 非本人的预约 → 4040（防水平越权，沿用 V1 语义）
        MvcResult foreign = start(u1, another.getId(), "PHONE", null);
        assertEquals(NOT_FOUND, code(foreign), "别人的预约应 4040，实际：" + body(foreign));
    }

    // ==================== T-V2-24 补测：把 PRD §12-7/13 那两档「放宽」真的钉住 ====================

    @Test
    @DisplayName("【补测T1】分区暂离天花板是活约束：同一原因在考研专座拿满 120 分钟、在静音区被夹到 60")
    void zoneLeaveCeilingIsLiveConstraint() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate tomorrow = tomorrowOf(LocalDate.now());

        // 六个种子原因的单次上限最长只有 60 分钟，分区侧 120（考研专座）与 60（静音区）因此分不出来。
        // 把就餐临时放宽到 180，让「分区天花板」成为真正的约束值 —— 这才是 PRD §12-7「放宽至 120min」
        // 可被验证的形态。整个测试 JVM 共用同一个内存库，所以用完必须在 finally 里复原。
        jdbc.update("update t_leave_reason set max_min = 180 where code = 'MEAL'");
        try {
            Reservation exam = checkedIn(uid, freeSeatOfZone(floor3, "3F-EXAM", tomorrow, 16, 8, 0),
                    floor3, tomorrow, 16, 8);
            JsonNode examLeave = startOk(me, exam.getId(), "MEAL", null);
            assertTrue(examLeave.path("remainMin").asInt() >= 118
                            && examLeave.path("remainMin").asInt() <= 120,
                    "考研专座应给满分区上限 120 分钟，实际 remainMin=" + examLeave.path("remainMin").asInt());

            // 同一人、同一原因，只换分区：静音区 60 立刻把 180 夹下来。两区之差就是分区配置本身。
            returnOpenLeave(me, exam.getId());
            Reservation silent = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", tomorrow, 16, 8, 0),
                    floor1, tomorrow, 16, 8);
            JsonNode silentLeave = startOk(me, silent.getId(), "MEAL", null);
            assertTrue(silentLeave.path("remainMin").asInt() >= 58
                            && silentLeave.path("remainMin").asInt() <= 60,
                    "静音区应被分区上限夹到 60 分钟，实际 remainMin=" + silentLeave.path("remainMin").asInt());
            returnOpenLeave(me, silent.getId());
        } finally {
            jdbc.update("update t_leave_reason set max_min = 60 where code = 'MEAL'");
        }
    }

    @Test
    @DisplayName("【补测T2】每日总次数挂在分区上：静音区第 4 次拒、考研专座第 5 次放行、第 6 次才拒")
    void dailyTotalLimitFollowsZone() throws Exception {
        LocalDate tomorrow = tomorrowOf(LocalDate.now());

        // 静音区（leave_daily_limit=3）：三人各自用满再试第 4 次。
        // ★ 必须各用一个新用户：总数是按「人 + 自然日」统计的，同一个人在静音区用过 3 次，
        //   到考研专座就只剩 2 次余量，那测的是余量而不是分区的上限值。
        String silentUser = newUser();
        long silentUid = userId(silentUser);
        for (int i = 0; i < 3; i++) {
            int start = 16 + i * 2;
            long seat = freeSeatOfZone(floor1, "1F-SILENT", tomorrow, start, 2, 0);
            Reservation r = checkedIn(silentUid, seat, floor1, tomorrow, start, 2);
            startOk(silentUser, r.getId(), "PHONE", null);
            returnOpenLeave(silentUser, r.getId());
        }
        int silentFourth = 22;
        Reservation fourth = checkedIn(silentUid,
                freeSeatOfZone(floor1, "1F-SILENT", tomorrow, silentFourth, 2, 0),
                floor1, tomorrow, silentFourth, 2);
        MvcResult rejected = start(silentUser, fourth.getId(), "REST", null);
        assertEquals(LEAVE_LIMIT_EXCEEDED, code(rejected),
                "静音区每日 3 次，第 4 次应 4096，实际：" + body(rejected));
        assertTrue(msg(rejected).contains("上限 3 次"), "应说明是本分区上限，实际：" + msg(rejected));

        // 考研专座（放宽至 5 次）：第 4、5 次必须放行，第 6 次才拒。
        // ★ V15 之前这里第 4 次就报 4096 —— 原因表的 3 被拿去 min() 夹分区的 5，"放宽"永远不可达，
        //   而「我的预约」按钮只看分区值，于是按钮亮着、提交被拒。
        String examUser = newUser();
        long examUid = userId(examUser);
        String[] reasons = {"PHONE", "PHONE", "PHONE", "REST", "REST"};
        for (int i = 0; i < reasons.length; i++) {
            int start = 16 + i * 2;
            long seat = freeSeatOfZone(floor3, "3F-EXAM", tomorrow, start, 2, 0);
            Reservation r = checkedIn(examUid, seat, floor3, tomorrow, start, 2);
            JsonNode ok = startOk(examUser, r.getId(), reasons[i], null);
            assertTrue(ok.path("remainMin").asInt() > 0, "考研专座第 " + (i + 1) + " 次应放行");
            returnOpenLeave(examUser, r.getId());
        }
        int sixthSlot = 26;
        long sixthSeat = freeSeatOfZone(floor3, "3F-EXAM", tomorrow, sixthSlot, 2, 0);
        Reservation sixth = checkedIn(examUid, sixthSeat, floor3, tomorrow, sixthSlot, 2);
        MvcResult sixthStart = start(examUser, sixth.getId(), "PICKUP", null);
        assertEquals(LEAVE_LIMIT_EXCEEDED, code(sixthStart),
                "考研专座放宽到 5 次，第 6 次才应 4096，实际：" + body(sixthStart));
        assertTrue(msg(sixthStart).contains("上限 5 次"), "实际：" + msg(sixthStart));

        // 置灰文案与提交报错一字不差 —— 两处判定同源才可能一致
        JsonNode vo = myReservation(examUser, sixth.getId(), "inProgress");
        assertEquals(false, vo.path("canLeave").asBoolean());
        assertEquals(msg(sixthStart), vo.path("leaveDisabledReason").asText(),
                "★「按钮亮着、提交被拒」是本轮修掉的口径分裂，用同一句话钉住两侧");
    }

    @Test
    @DisplayName("【补测T2b】原因各自每日次数第一次真正生效：就餐第 3 次 4096，但总数还剩、换原因仍可登记")
    void perReasonDailyLimitIsEnforced() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate today = LocalDate.now();
        int nowSlot = BizConstants.slotOf(LocalTime.now());

        for (int i = 0; i < 2; i++) {
            // 每次都取「当前全空闲」的第 0 座：上一条造数已把上一个座位标脏，天然错开
            long seat = freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 2, 0);
            Reservation r = checkedIn(uid, seat, floor1, today, nowSlot, 2);
            startOk(me, r.getId(), "MEAL", null);
            returnOpenLeave(me, r.getId());
        }

        Reservation third = checkedIn(uid, freeSeatOfZone(floor1, "1F-SILENT", today, nowSlot, 2, 0),
                floor1, today, nowSlot, 2);
        MvcResult mealExhausted = start(me, third.getId(), "MEAL", null);
        assertEquals(LEAVE_LIMIT_EXCEEDED, code(mealExhausted),
                "就餐每日 2 次（PRD §6.2 表），第 3 次应 4096，实际：" + body(mealExhausted));
        assertTrue(msg(mealExhausted).contains("就餐"),
                "必须点名是哪个原因用满了，实际：" + msg(mealExhausted));

        // 总数其实还没满（静音区 3 次，本人此刻只用了 2 次）→ 换一个原因仍可登记。
        // 这条断言证明「总数」与「单原因次数」真的是两条独立规则，而不是又一次 min()。
        startOk(me, third.getId(), "PHONE", null);
        returnOpenLeave(me, third.getId());

        JsonNode dict = data(exec(get("/api/leaves/reasons").header("Authorization", bearer(me))));
        JsonNode meal = null;
        JsonNode phone = null;
        for (JsonNode node : dict) {
            if ("MEAL".equals(node.path("code").asText())) {
                meal = node;
            } else if ("PHONE".equals(node.path("code").asText())) {
                phone = node;
            }
        }
        assertNotNull(meal);
        assertNotNull(phone);
        assertEquals(2, meal.path("todayUsed").asInt(), "字典要给出本人今日已用次数");
        assertEquals(true, meal.path("exhausted").asBoolean(), "就餐应标为已用满");
        assertTrue(meal.path("exhaustedReason").asText().contains("2 次"),
                "置灰必须给原因，实际：" + meal.path("exhaustedReason").asText());
        assertEquals(1, phone.path("todayUsed").asInt());
        assertEquals(false, phone.path("exhausted").asBoolean(), "接电话上限 3 次，还能用");
    }

    @Test
    @DisplayName("【补测T3】休闲阅览区同样不可暂离：4095 与按钮置灰给同一句原因")
    void loungeZoneRejectsLeave() throws Exception {
        String me = newUser();
        long uid = userId(me);
        LocalDate tomorrow = tomorrowOf(LocalDate.now());
        long seat = freeSeatOfZone(floor1, "1F-LOUNGE", tomorrow, 16, 4, 0);
        Reservation reservation = checkedIn(uid, seat, floor1, tomorrow, 16, 4);

        MvcResult rejected = start(me, reservation.getId(), "MEAL", null);
        assertEquals(LEAVE_NOT_ALLOWED, code(rejected),
                "休闲阅览区（沙发区，不设暂离）应 4095，实际：" + body(rejected));
        assertTrue(msg(rejected).contains("不支持暂离"), "实际：" + msg(rejected));

        JsonNode vo = myReservation(me, reservation.getId(), "inProgress");
        assertEquals(false, vo.path("canLeave").asBoolean());
        assertEquals(msg(rejected), vo.path("leaveDisabledReason").asText(),
                "★ 置灰原因与提交报错必须一字不差");
    }
}

package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.service.WaitlistStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S5 / T-V2-19：候补队列。
 *
 * <p>四条验收各守着一个不同的事故：
 * <ol>
 *   <li><b>候补不是第二条占座通道</b>：确认必须重新走完整校验链，否则排队就成了绕过信用分、
 *       单日累计与高峰限流的后门；</li>
 *   <li><b>一次释放只发一个 offer</b>：放出来的是一个位置，同时许诺给两个人等于注定让一人扑空；</li>
 *   <li><b>排队键不含座位</b>：写死 seat_id 的队列会因为「那把椅子又被别人占了」而整体卡死；</li>
 *   <li><b>超时不记违规</b>：候补是「没抢到」不是「放了鸽子」，记违规等于惩罚使用候补的人。</li>
 * </ol>
 *
 * <p>用例都在自建的小分区（2 座）里跑：种子分区有几十把椅子，要凑出「满座」得占一整层，
 * 而那些座位属于别的用例共用的内存库。
 */
class WaitlistApiTest extends ApiTestBase {

    /** 状态不允许（有空位不该排队、非 OFFERED 不该确认）。 */
    private static final int STATUS_NOT_ALLOWED = 4093;

    /** 座位时段冲突（确认时已无空位 / 并发抢座）。 */
    private static final int SLOT_CONFLICT = 4091;

    /** 分区单日累计超限。 */
    private static final int DAILY_CAP = 4098;

    /** 候补名额已满或重复排队。 */
    private static final int WAITLIST_LIMIT = 4106;

    /** 资源不存在（别人的候补按「不存在」回）。 */
    private static final int NOT_FOUND = 4040;

    /** 排队起始格（09:00）。 */
    private static final int START = slot(9);

    /** offer 窗口分钟数（规则默认值，用例只用来推进时钟，不改规则）。 */
    private static final int OFFER_MIN = 10;

    @Autowired
    private WaitlistStateService waitlistStateService;

    /** 本用例建的分区及其座位、候补记录，收尾必须清干净（分区是全局配置）。 */
    private Long createdZoneId;

    @AfterEach
    void cleanUp() throws Exception {
        if (createdZoneId == null) {
            return;
        }
        List<Long> seats = jdbc.queryForList("select id from t_seat where zone_id = ?",
                Long.class, createdZoneId);
        for (Long seatId : seats) {
            for (Long reservationId : jdbc.queryForList("select id from t_reservation where seat_id = ?"
                            + " and status in ('CONFIRMED','CHECKED_IN')", Long.class, seatId)) {
                release(reservationId, "候补用例收尾释放");
            }
            deleteSeat(seatId);
        }
        jdbc.update("delete from t_waitlist where zone_id = ?", createdZoneId);
        exec(delete("/api/staff/zones/" + createdZoneId).header("Authorization", bearer(admin)));
        createdZoneId = null;
    }

    // ==================== 验收 1：队首收 offer，过期顺延下一位 ====================

    @Test
    @DisplayName("【验收1】满座分区有人释放 → 队首收到 offer；队首过期后第二位在窗口内收到")
    void releaseOffersHeadAndExpiresToNext() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        String holder = newUser();
        String other = newUser();
        String first = newUser();
        String second = newUser();

        long reservation = reserveOk(holder, seats.get(0), date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(other, seats.get(1), date, START, 1)), "前置条件：第二把椅子也要占上");

        JsonNode head = joinOk(first, zoneId, date, START, 1);
        long headId = head.path("waitlistId").asLong();
        assertEquals("WAITING", head.path("status").asText());
        assertEquals(1, head.path("position").asInt(), "第一位应在队首");
        assertEquals(2, joinOk(second, zoneId, date, START, 1).path("position").asInt());
        assertEquals(2, mySingle(first).path("queueLength").asInt(),
                "★ 队列长度要在第二位的视图里读：第一位加入那一刻的长度本来就该是 1");

        assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                .header("Authorization", bearer(holder)))), "取消应成功");

        JsonNode offered = mySingle(first);
        assertEquals("OFFERED", offered.path("status").asText(), "★ 释放后队首要立刻收到 offer，而不是等下一轮扫描");
        assertTrue(offered.path("canConfirm").asBoolean(), "窗口内应可确认");
        assertNotNull(offered.path("offerExpireTime").asText(), "要给出截止时刻，用户才知道要不要快点");
        assertEquals("WAITING", mySingle(second).path("status").asText(), "一个位置不能同时许诺给两个人");

        // 用调度器的同一个入口推进时钟：判定仍由服务端按规则算，用例不伪造状态。
        // ★ 返回条数只断言「至少一条」：整个测试 JVM 共享同一个内存库，别的用例此刻也可能
        //   留有已过窗口的 OFFERED 行（计数类断言必须按人隔离，这是本项目定下的约定）。
        //   「恰好本队那一条被过期」由下面两行的状态断言精确锁住。
        assertTrue(waitlistStateService.expireOverdue(LocalDateTime.now().plusMinutes(OFFER_MIN + 1L)) >= 1,
                "过期扫描至少应处理本用例这一条");
        assertEquals("EXPIRED", jdbc.queryForObject("select status from t_waitlist where id = ?",
                String.class, headId), "队首未确认应置过期");
        JsonNode promoted = mySingle(second);
        assertEquals("OFFERED", promoted.path("status").asText(), "★ 位置应顺延给第二位");

        // 超时不能变成处罚：两人都没占到座位，谁也没放鸽子
        long firstId = uid(first);
        long secondId = uid(second);
        assertEquals(0, violationsOf(firstId) + violationsOf(secondId),
                "候补过期不得记违规，否则等于惩罚使用候补的人");
    }

    // ==================== 验收 2：确认走完整校验链 ====================

    @Test
    @DisplayName("【验收2】窗口内确认 → 服务端挑一座并生成正常预约（slot 行与状态同直接预约）")
    void confirmBooksThroughFullChain() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        String holder = newUser();
        String waiter = newUser();

        long reservation = reserveOk(holder, seats.get(0), date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();
        assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                .header("Authorization", bearer(holder)))));
        assertEquals("OFFERED", mySingle(waiter).path("status").asText(), "前置条件：队首已拿到 offer");

        MvcResult confirmed = exec(post("/api/waitlist/" + waitlistId + "/confirm")
                .header("Authorization", bearer(waiter)));
        assertEquals(0, code(confirmed), "确认候补应成功：" + body(confirmed));
        long newReservation = data(confirmed).path("reservationId").asLong();

        Integer slotRows = jdbc.queryForObject("select count(*) from t_reservation_slot"
                        + " where reservation_id = ?", Integer.class, newReservation),
                occupiedSeats = jdbc.queryForObject("select count(distinct seat_id) from t_reservation_slot"
                        + " where reservation_id = ?", Integer.class, newReservation);
        assertEquals(2, slotRows, "1 小时 = 2 个 30 分钟格，候补约出来的预约与直接约的没有区别");
        assertEquals(1, occupiedSeats, "一把椅子一格一行，不能出现跨座的两行");
        String status = jdbc.queryForObject("select status from t_reservation where id = ?",
                String.class, newReservation);
        assertEquals("CONFIRMED", status, "候补确认后的状态应与直接预约一致");

        assertEquals(0, jdbc.queryForObject("select count(*) from t_waitlist where id = ? and active_flag = 1",
                Integer.class, waitlistId), "已确认的候补应释放活动名额（active_flag 置 NULL）");
    }

    @Test
    @DisplayName("【验收2】分区单日累计超限 → 确认被 4098 拒掉，位置顺延下一位且不占位")
    void confirmIsNotAboveTheRules() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(60);
        List<Long> seats = zoneSeats();
        String holder = newUser();
        String waiter = newUser();
        String second = newUser();

        long reservation = reserveOk(holder, seats.get(0), date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        assertEquals(0, code(reserveRaw(waiter, seats.get(0), date, START + 4, 1)),
                "前置条件：让等待者在该分区当天先用满 60 分钟上限");
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();
        joinOk(second, zoneId, date, START, 1);
        assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                .header("Authorization", bearer(holder)))));

        MvcResult rejected = exec(post("/api/waitlist/" + waitlistId + "/confirm")
                .header("Authorization", bearer(waiter)));
        assertEquals(DAILY_CAP, code(rejected), "★ 候补不是后门，分区累计上限照样要拦：" + body(rejected));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_reservation_slot"
                        + " where seat_id = ? and reserve_date = ? and slot_index = ?",
                Integer.class, seats.get(0), LocalDate.parse(date), START),
                "确认失败后不该留下任何占用行");
        JsonNode promoted = mySingle(second);
        assertEquals("OFFERED", promoted.path("status").asText(), "确认不了就别占着队首，应顺延");
    }

    // ==================== 验收 3：重复排队与名额上限 ====================

    @Test
    @DisplayName("【验收3】同人同（分区,日期,起始时段）重复排队 → 4106，唯一索引兜底")
    void duplicateQueueRejected() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(0), date, START, 4)));
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 4)));

        String waiter = newUser();
        joinOk(waiter, zoneId, date, START, 1);
        MvcResult again = join(waiter, zoneId, date, START, 1);
        assertEquals(WAITLIST_LIMIT, code(again), "同一排队键重复排队应被拒：" + body(again));

        LocalDate day = LocalDate.parse(date);
        LocalDateTime now = LocalDateTime.now();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into t_waitlist (user_id, floor_id, zone_id, reserve_date, start_slot, end_slot,"
                        + " status, active_flag, create_time, update_time) values (?,?,?,?,?,?,?,1,?,?)",
                uid(waiter), floor1, zoneId, day, START, START + 2, "WAITING", now, now),
                "★ 应用层「先查再插」在并发下必然漏，唯一索引才是兜底");
    }

    @Test
    @DisplayName("【验收3】每人最多挂 2 条活动候补，第三条 4106；退出后名额立刻腾出")
    void maxActiveQueues() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(0), date, START, 4)));
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 4)));

        String waiter = newUser();
        long first = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();
        joinOk(waiter, zoneId, date, START + 2, 1);
        MvcResult third = join(waiter, zoneId, date, START + 4, 1);
        assertEquals(WAITLIST_LIMIT, code(third), "第三条应被名额拦下：" + body(third));
        assertTrue(msg(third).contains("2"), "要说清上限是几条：" + msg(third));

        assertEquals(0, code(exec(post("/api/waitlist/" + first + "/quit")
                .header("Authorization", bearer(waiter)))), "退出应成功");
        assertEquals(0, code(join(waiter, zoneId, date, START + 4, 1)),
                "★ 名额要在退出当轮就腾出，而不是等过期扫描");
    }

    // ==================== 验收 4：并发释放与确认 ====================

    @Test
    @DisplayName("【验收4】并发「直接预约」与「候补确认」抢同一格 → 只产生一条预约")
    void concurrentConfirmAndBookingDoNotDoubleBook() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        long seat = seats.get(0);
        String holder = newUser();
        String waiter = newUser();
        String rusher = newUser();

        long reservation = reserveOk(holder, seat, date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();
        assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                .header("Authorization", bearer(holder)))));
        assertEquals("OFFERED", mySingle(waiter).path("status").asText(), "前置条件：等待者已拿到 offer");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(await(start, () -> body(exec(post(
                    "/api/waitlist/" + waitlistId + "/confirm").header("Authorization", bearer(waiter)))).toString())));
            futures.add(pool.submit(await(start,
                    () -> body(reserveRaw(rusher, seat, date, START, 1)).toString())));
            start.countDown();
            List<String> responses = new ArrayList<>();
            for (Future<String> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            List<Integer> codes = new ArrayList<>();
            for (String response : responses) {
                codes.add(om.readTree(response).path("code").asInt());
            }
            java.util.Collections.sort(codes);
            System.out.println("[QA][候补并发] responses=" + responses);
            assertEquals(List.of(0, SLOT_CONFLICT), codes,
                    "★ 一把椅子只能属于一个人，实际 " + responses);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("select count(distinct reservation_id) from t_reservation_slot"
                        + " where seat_id = ? and reserve_date = ? and slot_index = ?",
                Integer.class, seat, LocalDate.parse(date), START),
                "★ 抢同一把椅子的同一格，最后只能有一个主人（另一条要么没落库，要么已回滚）");
    }

    @Test
    @DisplayName("【验收4】offer 不是留座：窗口内椅子被他人直接约走 → 确认 4091 并顺延，不留幽灵 offer")
    void offerDoesNotReserveTheSeat() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        String holder = newUser();
        String waiter = newUser();
        String second = newUser();

        long reservation = reserveOk(holder, seats.get(0), date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();
        joinOk(second, zoneId, date, START, 1);
        assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                .header("Authorization", bearer(holder)))));

        assertEquals(0, code(reserveRaw(newUser(), seats.get(0), date, START, 1)),
                "前置条件：有人赶在确认之前把释放的椅子直接约走");
        MvcResult late = exec(post("/api/waitlist/" + waitlistId + "/confirm")
                .header("Authorization", bearer(waiter)));
        assertEquals(SLOT_CONFLICT, code(late), "应如实告知没位置了：" + body(late));
        assertEquals("OFFERED", mySingle(second).path("status").asText(),
                "★ 队列要自愈：这一条作废并把 offer 交给下一位，而不是留一条永远确认不了的幽灵 offer");
    }

    @Test
    @DisplayName("【边界】有空位时不许排队；未轮到时确认被拒；别人排队不能替人退出")
    void guardsOnQueueEntryAndOwnership() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        String early = newUser();
        MvcResult notFull = join(early, zoneId, date, START, 1);
        assertEquals(STATUS_NOT_ALLOWED, code(notFull), "还有空位就该直接约：" + body(notFull));
        assertTrue(msg(notFull).contains("空位"), "要告诉用户还有几个空位：" + msg(notFull));

        List<Long> seats = zoneSeats();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(0), date, START, 1)));
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        String waiter = newUser();
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();

        MvcResult tooEarly = exec(post("/api/waitlist/" + waitlistId + "/confirm")
                .header("Authorization", bearer(waiter)));
        assertEquals(STATUS_NOT_ALLOWED, code(tooEarly), "没轮到就不能确认：" + body(tooEarly));

        MvcResult byStranger = exec(post("/api/waitlist/" + waitlistId + "/quit")
                .header("Authorization", bearer(newUser())));
        assertEquals(NOT_FOUND, code(byStranger), "别人的队列既不能确认也不能退出：" + body(byStranger));
        assertEquals("WAITING", mySingle(waiter).path("status").asText(),
                "★ 冒名请求不该改动任何状态：userId 只能取自登录态");
    }

    // ==================== 规则口径 ====================

    @Test
    @DisplayName("【规则驱动】waitlist.offer-min 真正决定确认窗口，且 /config/booking 同步给出")
    void offerWindowComesFromRule() throws Exception {
        String date = dw(2);
        long zoneId = twoSeatZone(null);
        List<Long> seats = zoneSeats();
        String holder = newUser();
        String waiter = newUser();
        long reservation = reserveOk(holder, seats.get(0), date, START, 1).path("reservationId").asLong();
        assertEquals(0, code(reserveRaw(newUser(), seats.get(1), date, START, 1)));
        long waitlistId = joinOk(waiter, zoneId, date, START, 1).path("waitlistId").asLong();

        try {
            assertEquals(0, code(updateRule(admin, "waitlist.offer-min", "1")), "确认窗口应收窄到 1 分钟");
            JsonNode config = data(exec(get("/api/config/booking").header("Authorization", bearer(u1))));
            assertEquals(1, config.path("waitlistOfferMin").asInt(),
                    "★ 候补入口的文案取自这份配置：写死 10 分钟就会在管理员改值后变成假说明");

            assertEquals(0, code(exec(post("/api/reservations/" + reservation + "/cancel")
                    .header("Authorization", bearer(holder)))));
            java.time.LocalDateTime offeredAt = jdbc.queryForObject(
                    "select offered_time from t_waitlist where id = ?", java.time.LocalDateTime.class, waitlistId);
            java.time.LocalDateTime expiresAt = jdbc.queryForObject(
                    "select offer_expire_time from t_waitlist where id = ?", java.time.LocalDateTime.class, waitlistId);
            assertEquals(1, java.time.Duration.between(offeredAt, expiresAt).toMinutes(),
                    "offer 的截止必须正好比发出晚一个「规则窗口」，而不是代码里的常量");
        } finally {
            updateRule(admin, "waitlist.offer-min", "10");
        }
    }

    // ==================== 工具 ====================

    private MvcResult updateRule(String token, String rkey, String value) throws Exception {
        return exec(put("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(List.of(Map.of("rkey", rkey, "value", value))))
                .header("Authorization", bearer(token)));
    }

    private <T> Callable<T> await(CountDownLatch start, Callable<T> task) {
        return () -> {
            start.await(5, TimeUnit.SECONDS);
            return task.call();
        };
    }

    private MvcResult join(String token, long zoneId, String date, int startSlot, int duration) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", zoneId);
        body.put("date", date);
        body.put("startSlot", startSlot);
        body.put("duration", duration);
        return exec(post("/api/waitlist").contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(token)));
    }

    private JsonNode joinOk(String token, long zoneId, String date, int startSlot, int duration) throws Exception {
        MvcResult result = join(token, zoneId, date, startSlot, duration);
        assertEquals(0, code(result), "加入候补应成功：" + body(result));
        return data(result);
    }

    private JsonNode mySingle(String token) throws Exception {
        JsonNode list = data(exec(get("/api/waitlist/my").header("Authorization", bearer(token))));
        assertEquals(1, list.size(), "本用例里每人只挂一条活动候补，实际 " + list.size());
        return list.get(0);
    }

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    private int violationsOf(long userId) {
        return jdbc.queryForObject("select count(*) from t_violation where user_id = ?",
                Integer.class, userId);
    }

    private List<Long> zoneSeats() {
        return jdbc.queryForList("select id from t_seat where zone_id = ? order by id",
                Long.class, createdZoneId);
    }

    /**
     * 建一个只有 2 个座位的分区（要能凑出「满座」才好造队列）。
     *
     * @param dailyCapMin 单日累计上限，null 表示不设
     */
    private long twoSeatZone(Integer dailyCapMin) throws Exception {
        String code = "WL" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        Map<String, Object> body = new HashMap<>();
        body.put("floorId", floor1);
        body.put("name", "候补用例区");
        body.put("code", code);
        body.put("zoneType", "SILENT");
        body.put("maxDurationMin", 240);
        body.put("dailyCapMin", dailyCapMin);
        body.put("advanceDays", 7);
        body.put("allowLeave", true);
        body.put("leaveMaxMin", 30);
        body.put("leaveDailyLimit", 3);
        body.put("allowDiscuss", "NO");
        body.put("visibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        body.put("eligibleRoles", List.of("STUDENT", "TEACHER", "LIBRARIAN", "SYS_ADMIN"));
        MvcResult created = exec(post("/api/staff/zones")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(created), "建分区应成功：" + body(created));
        long zoneId = data(created).path("zoneId").asLong();
        createdZoneId = zoneId;

        Map<String, Object> batch = new HashMap<>();
        batch.put("floorId", floor1);
        batch.put("zoneId", zoneId);
        batch.put("zone", "候补用例区");
        batch.put("rows", 1);
        batch.put("cols", 2);
        batch.put("prefix", code);
        batch.put("startNo", 1);
        batch.put("status", "AVAILABLE");
        // 坐标从 95 起：避开高峰用例（90）与种子座位，防止撞 uk（同格只能有一个座位）
        batch.put("rowFrom", 95);
        batch.put("colFrom", 95);
        MvcResult seats = exec(post("/api/admin/seats/batch")
                .contentType(MediaType.APPLICATION_JSON).content(json(batch))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(seats), "生成座位应成功：" + body(seats));
        assertEquals(2, data(seats).path("created").asInt(), "应生成 2 个座位");
        return zoneId;
    }
}

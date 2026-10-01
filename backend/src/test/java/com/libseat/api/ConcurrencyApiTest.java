package com.libseat.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P0-1 并发防重验证（DESIGN §4.3 C1 第 7 步）。
 *
 * <p>同一 seatId + date + slot 同时发起 N 个请求：
 * <ol>
 *   <li>恰好 1 个返回 code=0；</li>
 *   <li>其余返回 4091（座位时段冲突）或 4092（同一用户重叠）；</li>
 *   <li>{@code t_reservation_slot} 该座位该时段恰好 {@code duration} 行；</li>
 *   <li>{@code t_reservation} 主表不得留下孤儿记录（失败事务必须完整回滚）。</li>
 * </ol>
 */
class ConcurrencyApiTest extends ApiTestBase {

    private static final int THREADS = 8;

    private int createReservation(String token, long seatId, String date, int startSlot, int duration)
            throws Exception {
        String payload = om.writeValueAsString(Map.of(
                "seatId", seatId, "date", date, "startSlot", startSlot, "duration", duration));
        MvcResult result = mvc.perform(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("Authorization", bearer(token)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").asInt();
    }

    @Test
    @DisplayName("【P0-1】8 个不同用户并发抢同一座位同一时段：恰好 1 个成功，其余 4091，DB 无脏数据")
    void concurrentSameSeatDifferentUsers() throws Exception {
        long seat = seatId(floor1, "A10");
        String date = dw(3);
        int startSlot = slot(14);
        int duration = 2;

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tokens.add(newUser());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return createReservation(token, seat, date, startSlot, duration);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未就绪");
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }

            long success = codes.stream().filter(c -> c == 0).count();
            long conflict = codes.stream().filter(c -> c == 4091).count();
            long overlap = codes.stream().filter(c -> c == 4092).count();

            System.out.println("[QA][并发-不同用户] codes=" + codes
                    + " success=" + success + " conflict4091=" + conflict + " overlap4092=" + overlap);

            assertEquals(1, success, "★ 恰好应有 1 个请求成功，实际 codes=" + codes);
            assertEquals(THREADS - 1, conflict + overlap,
                    "其余请求应全部是 4091/4092，实际 codes=" + codes);

            // ---- DB 落库校验 ----
            long slotRows = jdbc.queryForObject(
                    "select count(*) from t_reservation_slot where seat_id = ? and reserve_date = ? "
                            + "and slot_index between ? and ?",
                    Long.class, seat, java.time.LocalDate.parse(date), startSlot,
                    startSlot + com.libseat.common.BizConstants.slotsOf(duration) - 1);
            assertEquals(com.libseat.common.BizConstants.slotsOf(duration), slotRows,
                    "★ t_reservation_slot 该座位该时段应恰好 "
                            + com.libseat.common.BizConstants.slotsOf(duration) + " 行，实际 " + slotRows);

            long mainRows = jdbc.queryForObject(
                    "select count(*) from t_reservation where seat_id = ? and reserve_date = ? and start_slot = ? "
                            + "and status = 'CONFIRMED'",
                    Long.class, seat, java.time.LocalDate.parse(date), startSlot);
            assertEquals(1, mainRows, "★ t_reservation 主表应恰好 1 条有效预约，实际 " + mainRows);

            long orphan = jdbc.queryForObject(
                    "select count(*) from t_reservation r where r.seat_id = ? and r.reserve_date = ? "
                            + "and r.start_slot = ? and r.status = 'CONFIRMED' "
                            + "and not exists (select 1 from t_reservation_slot s where s.reservation_id = r.id)",
                    Long.class, seat, java.time.LocalDate.parse(date), startSlot);
            assertEquals(0, orphan, "★ 不应存在「有主表记录但无 slot 行」的半成品数据");

            long globalOrphan = jdbc.queryForObject("select count(*) from t_reservation_slot s "
                    + "where not exists (select 1 from t_reservation r where r.id = s.reservation_id)", Long.class);
            assertEquals(0, globalOrphan, "★ t_reservation_slot 不应存在指向不存在预约的孤儿行");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("【P0-1】同一用户并发重复提交：最多 1 个成功，不允许出现重复预约")
    void concurrentSameUser() throws Exception {
        long seat = seatId(floor1, "A11");
        String date = dw(3);
        int startSlot = slot(16);
        int duration = 2;
        String token = newUser();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return createReservation(token, seat, date, startSlot, duration);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = codes.stream().filter(c -> c == 0).count();
            System.out.println("[QA][并发-同一用户] codes=" + codes + " success=" + success);
            assertEquals(1, success, "★ 同一用户并发最多只允许 1 条成功，实际 codes=" + codes);

            long mainRows = jdbc.queryForObject(
                    "select count(*) from t_reservation where seat_id = ? and reserve_date = ? and start_slot = ? "
                            + "and status in ('CONFIRMED','CHECKED_IN')",
                    Long.class, seat, java.time.LocalDate.parse(date), startSlot);
            assertEquals(1, mainRows, "★ 同一用户并发后应只有 1 条有效预约，实际 " + mainRows);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("【P0-1】并发抢同一楼层不同座位：都应成功且互不串座")
    void concurrentDifferentSeats() throws Exception {
        long seatA = seatId(floor1, "A12");
        long seatB = seatId(floor1, "A13");
        String date = dw(3);
        int startSlot = slot(9);

        List<String> tokens = List.of(newUser(), newUser());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createReservation(tokens.get(0), seatA, date, startSlot, 2);
            }));
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createReservation(tokens.get(1), seatB, date, startSlot, 2);
            }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            System.out.println("[QA][并发-不同座位] codes=" + codes);
            assertEquals(List.of(0, 0), codes, "不同座位并发都应成功，实际 " + codes);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("【P0-1】同一座位不同日期并发：都应成功（预约窗口不冲突）")
    void concurrentDifferentDates() throws Exception {
        long seat = seatId(floor1, "A14");
        Collections.emptyList();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        String t1 = newUser();
        String t2 = newUser();
        try {
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createReservation(t1, seat, d(4), slot(10), 2);
            }));
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return createReservation(t2, seat, d(5), slot(10), 2);
            }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(30, TimeUnit.SECONDS));
            }
            System.out.println("[QA][并发-不同日期] codes=" + codes);
            assertEquals(List.of(0, 0), codes, "不同日期的同座位应都可预约，实际 " + codes);
        } finally {
            pool.shutdownNow();
        }
    }
}

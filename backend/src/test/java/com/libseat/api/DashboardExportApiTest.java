package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.Reservation;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.ReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * V2 S6 / T-V2-22：监控大屏、台账导出与规则变更历史。
 *
 * <p>要守住的口径：
 * <ol>
 *   <li><b>大屏不另算一份占用</b>：楼层座位数加起来必须等于概览的全馆座位数，
 *       两处不一样时馆员不知道该信哪个（占用本身仍由 {@code OccupancyService} 单点派生）；</li>
 *   <li><b>CSV 是给 Excel 双击看的</b>：没有 BOM 就是乱码，没有引号转义就是一行撑成两列，
 *       没有公式引导字符防护就是把一个可执行入口交给导出内容；</li>
 *   <li><b>导出留审计</b>：一份台账是成批个人信息，「谁在什么时候带走了哪一批」必须可查；</li>
 *   <li><b>规则历史读审计里的 oldValue/newValue</b>：同一键改两次，第二条要显示 20→25 而不是 25→25。</li>
 * </ol>
 */
class DashboardExportApiTest extends ApiTestBase {

    /** 无权限。 */
    private static final int FORBIDDEN = 4030;

    /** 参数错误。 */
    private static final int PARAM_ERROR = 4001;

    /** UTF-8 BOM 的三个字节。 */
    private static final int[] BOM = {0xEF, 0xBB, 0xBF};

    /** 预约台账的列数（加字段时页面与导出必须同时改，这里就是那道闸）。 */
    private static final int RESERVATION_COLUMNS = 14;

    /** 大屏单接口耗时上限（宽松值，只挡「逐行再查一次库」这类事故；PRD 的 1s 由演示库实测给出）。 */
    private static final long DASHBOARD_BUDGET_MS = 3000L;

    @Autowired
    private ReservationRepository reservationRepository;

    // ==================== 大屏 ====================

    @Test
    @DisplayName("【验收1】大屏单接口：馆员可读、学生 4030，且楼层合计与概览同一出处")
    void dashboardIsStaffOnlyAndSelfConsistent() throws Exception {
        assertEquals(FORBIDDEN, code(exec(get("/api/staff/dashboard").header("Authorization", bearer(u1)))),
                "大屏虽只给数字，也属于馆员面：全馆运行状态不该由学生读到");

        long start = System.nanoTime();
        JsonNode dash = data(exec(get("/api/staff/dashboard").header("Authorization", bearer(admin))));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(dash.path("overview").hasNonNull("totalSeats"), "概览复用统计页的 overview：" + dash);
        long floorSeats = 0;
        for (JsonNode floor : dash.path("floors")) {
            assertTrue(floor.path("occupied").asLong() <= floor.path("totalSeats").asLong(),
                    "占用不可能超过座位数：" + floor);
            assertFalse(floor.path("zones").isEmpty(), "分区占用是大屏的核心一格，楼层下必须带分区：" + floor);
            floorSeats += floor.path("totalSeats").asLong();
        }
        assertEquals(dash.path("overview").path("totalSeats").asLong(), floorSeats,
                "★ 楼层加起来的座位数要等于概览的全馆座位数，不相等就说明有一处另算了口径");
        assertFalse(dash.path("heat").isEmpty(), "热力不能是空的");
        assertTrue(dash.path("slotLabel").asText().contains("-"), "当前时段要给文案，别让前端自己拼：" + dash);
        assertTrue(elapsedMs < DASHBOARD_BUDGET_MS,
                "单接口聚合耗时 " + elapsedMs + "ms 超过 " + DASHBOARD_BUDGET_MS + "ms，疑似逐行查询");
    }

    @Test
    @DisplayName("【验收1】大屏的疑点数与暂离数随真实数据变化（不是写死的 0）")
    void dashboardAnomaliesTrackRealRows() throws Exception {
        String date = dw(1);
        int startSlot = slot(14);
        long patrolBefore = dashboard().path("anomalies").path("patrolOpen").asLong();
        long leaveBefore = dashboard().path("onLeave").asLong();

        long seat = freeSeat(date, startSlot);
        String student = newUser();
        long reservationId = reserveOk(student, seat, date, startSlot, 4).path("reservationId").asLong();
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setCheckInTime(BizConstants.slotStart(LocalDate.parse(date), startSlot));
        reservationRepository.saveAndFlush(reservation);

        long patrolId = registerPatrol(seat, "登记一条疑点，用来验证大屏计数").path("patrolId").asLong();

        JsonNode after = dashboard();
        assertEquals(patrolBefore + 1, after.path("anomalies").path("patrolOpen").asLong(),
                "★ 新增一条未处理疑点，大屏必须多一个");
        assertTrue(after.path("anomalies").path("patrolMid").asLong() >= 1, "人工登记默认中，等级计数要跟上");
        assertTrue(after.path("anomalies").path("patrolOldestMinutes").asLong() >= 0);

        MvcResult left = exec(startLeave(reservationId, student));
        assertEquals(0, code(left), "前置条件：暂离要开成功，否则这一格没法验：" + body(left));
        assertEquals(leaveBefore + 1, dashboard().path("onLeave").asLong(),
                "暂离保留中的座位仍被占着，这个数漏计就等于叫馆员别去看");

        jdbc.update("delete from t_leave_record where reservation_id = ?", reservationId);
        jdbc.update("delete from t_patrol_task where id = ?", patrolId);
        jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservationId);
        jdbc.update("delete from t_reservation where id = ?", reservationId);
    }

    // ==================== CSV 导出 ====================

    @Test
    @DisplayName("【验收2】预约台账：UTF-8 BOM + 中文表头 + 行数与库内条数一致")
    void reservationLedgerIsExcelFriendly() throws Exception {
        String date = dw(1);
        int startSlot = slot(15);
        long seat = freeSeat(date, startSlot);
        String student = newUser();
        long reservationId = reserveOk(student, seat, date, startSlot, 2).path("reservationId").asLong();

        MockHttpServletResponse response = exec(get("/api/admin/export/reservation")
                .param("dateFrom", date).param("dateTo", date)
                .header("Authorization", bearer(admin))).getResponse();
        String csv = csvOf(response);

        assertBom(response);
        assertTrue(csv.startsWith("\"预约号\",\"用户ID\""), "表头必须是中文业务名：" + csvHead(csv));
        assertEquals(RESERVATION_COLUMNS, headerColumns(csv), "表头列数固定，加字段时页面与导出要一起改");
        // 失败时要把两边的 ID 都打出来：少了哪一条、它是什么状态，比「差一行」可诊断得多
        List<Long> exported = dataLines(csv).stream()
                .map(line -> Long.valueOf(line.substring(1, line.indexOf("\","))))
                .toList();
        List<Long> inDb = jdbc.queryForList("select id from t_reservation where reserve_date = ? order by id",
                Long.class, date);
        assertEquals(inDb.size(), exported.size(),
                "日期 " + date + " 的台账对不上：库里 " + inDb + "，导出 " + exported);
        assertTrue(exported.containsAll(inDb) && inDb.containsAll(exported),
                "导出的预约集合要和库里这一天的一模一样：库 " + inDb + " / 导出 " + exported);
        assertTrue(csv.contains("\"" + date + "\""), "台账要能按日期核对：" + csvHead(csv));

        jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservationId);
        jdbc.update("delete from t_reservation where id = ?", reservationId);
    }

    @Test
    @DisplayName("【验收2】CSV 安全性：逗号与引号不撑列，= 开头的值不得变成 Excel 公式")
    void csvEscapesSeparatorsAndBlocksFormulaInjection() throws Exception {
        long seat = seatId(floor1, "A12");
        long patrolId = registerPatrol(seat, "前置登记，只为占住这个座位的未处理名额").path("patrolId").asLong();

        // 处置说明是馆员自由填写的文本，这里同时放三种坑：公式引导符、逗号、双引号
        Map<String, Object> handle = new HashMap<>();
        handle.put("action", "REMIND");
        handle.put("note", "=SUM(1,2),内嵌\"引号\"与逗号");
        assertEquals(0, code(exec(post("/api/staff/patrol/" + patrolId + "/handle")
                .contentType(MediaType.APPLICATION_JSON).content(json(handle))
                .header("Authorization", bearer(librarian())))));

        String csv = csvOf(exec(get("/api/admin/export/patrol").header("Authorization", bearer(admin)))
                .getResponse());
        String hit = dataLines(csv).stream()
                .filter(line -> line.contains("内嵌")).findFirst().orElse("");
        assertFalse(hit.isEmpty(), "处置说明要出现在巡查台账里：" + csvHead(csv));
        assertEquals(headerColumns(csv), columns(hit),
                "★ 含逗号与引号的说明把一行撑成了多列，说明转义没生效：" + hit);
        assertTrue(hit.contains("\"\"引号\"\""), "内嵌双引号必须成对转义：" + hit);
        assertTrue(hit.contains("'=SUM(1,2)"),
                "以 = 开头的单元格必须前置单引号，否则 Excel 双击打开就会执行它：" + hit);

        jdbc.update("delete from t_patrol_task where seat_id = ?", seat);
    }

    @Test
    @DisplayName("【验收2】违规与信用台账：表头齐备，信用台账是全员快照且「当前生效处罚」按判活口径给")
    void violationAndCreditLedgersAreComplete() throws Exception {
        MockHttpServletResponse violation = exec(get("/api/admin/export/violation")
                .header("Authorization", bearer(admin))).getResponse();
        assertBom(violation);
        assertTrue(csvOf(violation).startsWith("\"违规号\",\"用户ID\""), csvHead(csvOf(violation)));

        MockHttpServletResponse credit = exec(get("/api/admin/export/credit")
                .header("Authorization", bearer(admin))).getResponse();
        assertBom(credit);
        String csv = csvOf(credit);
        assertTrue(csv.startsWith("\"用户ID\",\"学号\",\"姓名\",\"角色\",\"信用分\",\"当前生效处罚\""), csvHead(csv));
        long users = jdbc.queryForObject("select count(*) from t_user", Long.class);
        assertEquals(users, dataLines(csv).size(), "信用台账是全员快照，少一个人就等于台账不可信");

        // 处罚列的「生效中」必须与 Penalty#activeAt 同源：同一个人造两条 ACTIVE 行，一条已过期、一条仍在期内。
        // 台账导出的是处罚类型的业务文案（暂停预约 3 天 / 7 天），不是内部审计用的 reason 字段。
        long studentId = jdbc.queryForObject("select id from t_user where username = 'student1'", Long.class);
        long existing = jdbc.queryForObject("select count(*) from t_penalty where user_id = ?",
                Long.class, studentId);
        assertTrue(existing == 0,
                "前置条件：student1 在测试库里不应已有处罚行，否则「文案出现/不出现」两边都可能假绿：" + existing);
        LocalDateTime anchor = BizConstants.slotStart(LocalDate.parse(dw(1)), 20);
        jdbc.update("insert into t_penalty (user_id, type, start_time, end_time, reason, status, create_time) "
                        + "values (?, 'BOOK_BAN_3D', ?, ?, '已过期行仍是 ACTIVE', 'ACTIVE', ?)",
                studentId, anchor.minusDays(3), anchor.minusDays(1), anchor.minusDays(3));
        jdbc.update("insert into t_penalty (user_id, type, start_time, end_time, reason, status, create_time) "
                        + "values (?, 'BOOK_BAN_7D', ?, ?, '仍在期内', 'ACTIVE', ?)",
                studentId, anchor.minusDays(1), anchor.plusDays(6), anchor.minusDays(1));
        try {
            String row = dataLines(csvOf(exec(get("/api/admin/export/credit")
                    .header("Authorization", bearer(admin))).getResponse())).stream()
                    .filter(line -> line.startsWith("\"" + studentId + "\","))
                    .findFirst().orElse("");
            assertFalse(row.isEmpty(), "台账里要能找到 student1 这一行");
            assertTrue(row.contains("暂停预约 7 天"), "仍在期内的处罚必须进「当前生效处罚」列：" + row);
            assertFalse(row.contains("暂停预约 3 天"),
                    "★ 已过结束时刻的行虽然 status 仍是 ACTIVE，也不能算当前生效（判活口径只有一个出处）：" + row);
        } finally {
            jdbc.update("delete from t_penalty where reason in ('已过期行仍是 ACTIVE', '仍在期内')");
        }
    }

    @Test
    @DisplayName("【验收2】导出权限与参数：馆员与学生都 4030，未知类型与倒置日期 4001")
    void exportIsAdminOnlyWithParameterGuards() throws Exception {
        assertEquals(FORBIDDEN, code(exec(get("/api/admin/export/patrol").header("Authorization", bearer(u1)))));
        assertEquals(FORBIDDEN, code(exec(get("/api/admin/export/patrol").header("Authorization", bearer(librarian())))),
                "馆员能看看板与申诉队列，但拿走整本台账是管理职责，不是楼层巡查的一部分");

        MvcResult unknown = exec(get("/api/admin/export/whatever").header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(unknown), body(unknown).toString());
        assertTrue(msg(unknown).contains("reservation"), "报错要把可选项说清：" + msg(unknown));

        MvcResult reversed = exec(get("/api/admin/export/reservation")
                .param("dateFrom", dw(1)).param("dateTo", d(-1))
                .header("Authorization", bearer(admin)));
        assertEquals(PARAM_ERROR, code(reversed), body(reversed).toString());
    }

    @Test
    @DisplayName("【验收2】每次导出都留审计（类型、文件名、行数）")
    void exportIsAuditedWithRowCount() throws Exception {
        int before = auditRows("patrol");
        exec(get("/api/admin/export/patrol").header("Authorization", bearer(admin)));

        assertEquals(before + 1, auditRows("patrol"), "导出必须留一条审计，否则成批个人信息外带无据可查");
        String detail = jdbc.queryForObject(
                "select detail from t_audit_log where action = 'DATA_EXPORT' and target_id = 'patrol' "
                        + "order by id desc limit 1", String.class);
        JsonNode parsed = om.readTree(detail);
        assertEquals("patrol", parsed.path("type").asText());
        assertTrue(parsed.path("fileName").asText().startsWith("patrol-")
                        && parsed.path("fileName").asText().endsWith(".csv"),
                "文件名要进审计，事后才知道带走的是哪一份：" + parsed);
        assertTrue(parsed.path("rows").asInt() >= 0, "行数要进审计：" + parsed);
    }

    // ==================== 规则变更历史 ====================

    @Test
    @DisplayName("【验收3】规则历史读审计的 oldValue/newValue：同一键改两次，两条前后值各自正确")
    void ruleHistoryReportsOldAndNewValuePerChange() throws Exception {
        String rkey = "patrol.escalate-min";
        String original = jdbc.queryForObject("select rvalue from t_rule_config where rkey = ?", String.class, rkey);
        try {
            updateRule(rkey, "20");
            updateRule(rkey, "25");

            List<JsonNode> history = historyOf(rkey);
            assertTrue(history.size() >= 2, "两次改动至少要留下两条历史：" + history);
            JsonNode newest = history.get(0);
            JsonNode second = history.get(1);
            assertEquals("30", second.path("oldValue").asText(), "第一条的旧值是迁移播种值：" + second);
            assertEquals("20", second.path("newValue").asText(), "第一条的新值是第一次改的目标：" + second);
            assertEquals("20", newest.path("oldValue").asText(),
                    "★ 第二条的旧值必须是上一次的 20，不能拿「当前值」充当历史旧值：" + newest);
            assertEquals("25", newest.path("newValue").asText());
            assertTrue(newest.path("description").asText().length() > 0, "历史行要能看懂改的是哪条规则：" + newest);
            assertTrue(newest.path("operatorName").asText().length() > 0, "改规则的人必须能看出来：" + newest);
            assertTrue(newest.path("createTime").asText().compareTo(second.path("createTime").asText()) >= 0,
                    "历史按时间倒序，最新一条在前");
            for (JsonNode row : history) {
                assertEquals(rkey, row.path("rkey").asText(), "按 rkey 过滤后不能混进别的规则：" + row);
            }
        } finally {
            updateRule(rkey, original);
        }
    }

    // ==================== 工具 ====================

    private JsonNode dashboard() throws Exception {
        return data(exec(get("/api/staff/dashboard").header("Authorization", bearer(admin))));
    }

    private String librarian() throws Exception {
        return login("librarian1", "123456");
    }

    private JsonNode registerPatrol(long seat, String note) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("seatId", seat);
        body.put("note", note);
        MvcResult created = exec(post("/api/staff/patrol").contentType(MediaType.APPLICATION_JSON)
                .content(json(body)).header("Authorization", bearer(librarian())));
        assertEquals(0, code(created), "前置条件：登记疑点要成功：" + body(created));
        return data(created);
    }

    /** 暂离必须由预约本人开（入口按 token 认人），所以这里带的是刚注册的那个学生。 */
    private MockHttpServletRequestBuilder startLeave(long reservationId, String token) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reservationId", reservationId);
        body.put("reasonCode", "REST");
        return post("/api/leaves").contentType(MediaType.APPLICATION_JSON)
                .content(json(body)).header("Authorization", bearer(token));
    }

    private void updateRule(String rkey, String value) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("rkey", rkey);
        item.put("value", value);
        MvcResult saved = exec(put("/api/admin/rules").contentType(MediaType.APPLICATION_JSON)
                .content(json(List.of(item))).header("Authorization", bearer(admin)));
        assertEquals(0, code(saved), "规则更新应成功：" + body(saved));
    }

    private List<JsonNode> historyOf(String rkey) throws Exception {
        JsonNode list = data(exec(get("/api/admin/rules/history").param("rkey", rkey)
                .header("Authorization", bearer(admin))));
        List<JsonNode> out = new ArrayList<>();
        list.forEach(out::add);
        return out;
    }

    private int auditRows(String targetId) {
        return jdbc.queryForObject("select count(*) from t_audit_log where action = 'DATA_EXPORT' and target_id = ?",
                Integer.class, targetId);
    }

    private void assertBom(MockHttpServletResponse response) {
        byte[] bytes = response.getContentAsByteArray();
        assertTrue(bytes.length > BOM.length, "空文件不能算导出成功");
        for (int i = 0; i < BOM.length; i++) {
            assertEquals(BOM[i], bytes[i] & 0xFF, "第 " + (i + 1) + " 个字节不是 UTF-8 BOM，Excel 会按本地代码页猜");
        }
    }

    private String csvOf(MockHttpServletResponse response) {
        byte[] bytes = response.getContentAsByteArray();
        return new String(bytes, BOM.length, bytes.length - BOM.length, StandardCharsets.UTF_8);
    }

    /**
     * 数据行（去掉表头）。
     *
     * <p>按 {@code \r\n} 切行，不处理「引号内含换行」这种极端情况 ——
     * 本类的用例不含换行值，真要处理得写一个 CSV parser，而这里要守的是 BOM、列数与转义。
     */
    private List<String> dataLines(String csv) {
        List<String> lines = new ArrayList<>();
        for (String raw : csv.split("\r\n")) {
            if (!raw.isBlank()) {
                lines.add(raw);
            }
        }
        return lines.subList(1, lines.size());
    }

    private int headerColumns(String csv) {
        return columns(csv.split("\r\n")[0]);
    }

    private int columns(String line) {
        return line.split("\",\"", -1).length;
    }

    private String csvHead(String csv) {
        return csv.substring(0, Math.min(csv.length(), 160));
    }

    /** 指定时段空闲的 1F 静音区座位（与巡查看板同一取法）。 */
    private long freeSeat(String date, int slot) throws Exception {
        JsonNode map = data(exec(get("/api/seats/map")
                .param("floorId", String.valueOf(floor1))
                .param("date", date)
                .param("slot", String.valueOf(slot))
                .header("Authorization", bearer(admin))));
        long silent = jdbc.queryForObject("select id from t_zone where code = '1F-SILENT'", Long.class);
        for (JsonNode seat : map.path("seats")) {
            if ("FREE".equals(seat.path("displayStatus").asText()) && seat.path("zoneId").asLong() == silent) {
                return seat.path("seatId").asLong();
            }
        }
        throw new AssertionError(date + " 第 " + slot + " 格找不到空闲的静音区座位");
    }
}

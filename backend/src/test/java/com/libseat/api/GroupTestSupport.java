package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.Group;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.GroupRepository;
import com.libseat.scheduler.ReservationScheduler;
import com.libseat.service.RuleConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 组队用例的共同底座（T-V2-15 / T-V2-16 共用）。
 *
 * <p>★ 抽出来而不是各写一份：桌位生成会在 1F 研讨协作区真实占行占列，收尾必须
 * 「先释放预约 → 再删座位 → 最后删桌」，这套顺序写两遍就会有一遍漏掉某步，
 * 表现为别的用例偶发失败（座位数与占用是共享内存库里的全局状态）。
 */
abstract class GroupTestSupport extends ApiTestBase {

    /** 分区仅整组预约。 */
    protected static final int GROUP_ONLY_ZONE = 4102;

    /** 已锁定，禁止增减成员。 */
    protected static final int GROUP_LOCKED = 4104;

    /** 邀请码无效或过期。 */
    protected static final int GROUP_INVITE_INVALID = 4105;

    /** 座位已被抢。 */
    protected static final int SEAT_CONFLICT = 4091;

    /** 越权（非组长做组长动作）。 */
    protected static final int ROLE_NOT_ELIGIBLE = 4031;

    /** 状态不允许。 */
    protected static final int STATUS_NOT_ALLOWED = 4093;

    /** 参数不合法（人数上下限都从这里出）。 */
    protected static final int PARAM_ERROR = 4001;

    /** 组队预约时长（小时）。 */
    protected static final int DURATION_HOURS = 2;

    /** 对应 30 分钟格数。 */
    protected static final int SLOT_COUNT = DURATION_HOURS * BizConstants.SLOTS_PER_HOUR;

    /** 成员缺席宽限（分钟）。 */
    protected static final int GRACE_MIN = BizConstants.GROUP_ATTEND_GRACE_MIN;

    @Autowired
    protected GroupRepository groupRepository;

    @Autowired
    protected ReservationScheduler scheduler;

    /** 签到窗口等规则以规则中心的实时值为准，用例不复制常量。 */
    @Autowired
    protected RuleConfigService ruleConfigService;

    /** 1F 研讨协作区（种子中唯一的 group_required 分区）。 */
    protected long seminarZoneId() throws Exception {
        JsonNode list = data(exec(get("/api/zones")
                .param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(admin))));
        for (JsonNode node : list) {
            if ("1F-SEMINAR".equals(node.path("code").asText())) {
                return node.path("zoneId").asLong();
            }
        }
        throw new AssertionError("种子中应有 1F-SEMINAR 研讨协作区");
    }

    protected String date() {
        return dw(1);
    }

    protected int startSlot() {
        return slot(9);
    }

    protected long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }

    /** 建一张桌并生成 count 个相邻座位，返回桌 ID。 */
    protected long newTable(int count) throws Exception {
        String tableNo = "GRP" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", seminarZoneId());
        body.put("tableNo", tableNo);
        body.put("seatCount", count);
        body.put("status", "AVAILABLE");
        MvcResult created = exec(post("/api/staff/tables")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(created), "建桌应成功：" + body(created));
        long tableId = data(created).path("tableId").asLong();
        MvcResult generated = exec(post("/api/staff/tables/" + tableId + "/seats/generate")
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(generated), "按桌生成座位应成功：" + body(generated));
        return tableId;
    }

    /** 桌内座位 ID，按座位编号排序（与锁定下单的分配顺序同一口径）。 */
    protected List<Long> tableSeats(long tableId) {
        return jdbc.queryForList("select id from t_seat where table_id = ? order by seat_no",
                Long.class, tableId);
    }

    /** 注册一个全新用户，返回 {账号, token}。 */
    protected String[] newMember() throws Exception {
        String username = "qb" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        Map<String, Object> req = new HashMap<>();
        req.put("username", username);
        req.put("password", "123456");
        req.put("confirmPassword", "123456");
        req.put("realName", "组队成员" + username.substring(2));
        MvcResult registered = exec(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json(req)));
        assertEquals(0, code(registered), "注册失败：" + body(registered));
        return new String[]{username, login(username, "123456")};
    }

    protected long createGroup(String token, long tableId) throws Exception {
        MvcResult result = createGroupRaw(token, tableId, null, null);
        assertEquals(0, code(result), "建队应成功：" + body(result));
        return data(result).path("groupId").asLong();
    }

    /** 建队的原始入口：要断言「被拒」与「人数上下限」时必须能显式传值（null 表示用服务端默认）。 */
    protected MvcResult createGroupRaw(String token, long tableId, Integer maxMembers, Integer minMembers)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", seminarZoneId());
        body.put("tableId", tableId);
        body.put("date", date());
        body.put("startSlot", startSlot());
        body.put("duration", DURATION_HOURS);
        if (maxMembers != null) {
            body.put("maxMembers", maxMembers);
        }
        if (minMembers != null) {
            body.put("minMembers", minMembers);
        }
        return exec(post("/api/groups")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(token)));
    }

    /** 建队 + 邀请 count 人并让其确认，返回队伍 ID（多个用例都需要这个「已就绪」现场）。 */
    protected long createConfirmedGroup(String leader, long tableId, List<String[]> members)
            throws Exception {
        long groupId = createGroup(leader, tableId);
        for (String[] member : members) {
            MvcResult invited = invite(leader, groupId, member[0]);
            assertEquals(0, code(invited), "邀请 " + member[0] + " 应成功：" + body(invited));
        }
        for (String[] member : members) {
            MvcResult confirmed = confirm(member[1], groupId);
            assertEquals(0, code(confirmed), "确认应成功：" + body(confirmed));
        }
        return groupId;
    }

    protected MvcResult invite(String token, long groupId, String username) throws Exception {
        return exec(post("/api/groups/" + groupId + "/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username)))
                .header("Authorization", bearer(token)));
    }

    protected MvcResult confirm(String token, long groupId) throws Exception {
        return exec(post("/api/groups/" + groupId + "/confirm").header("Authorization", bearer(token)));
    }

    protected JsonNode detail(String token, long groupId) throws Exception {
        return data(exec(get("/api/groups/" + groupId).header("Authorization", bearer(token))));
    }

    /** 把锁定时刻推到当下之前（用例不靠 sleep 等时刻）。 */
    protected void pushLockPast(long groupId) {
        groupRepository.findById(groupId).ifPresent(group -> {
            group.setLockTime(LocalDateTime.now().minusMinutes(1));
            groupRepository.saveAndFlush(group);
        });
    }

    /** 推到点后跑真实扫描链路，验证「到点自动锁定下单」这条触发路径本身。 */
    protected void lockNow(long groupId) {
        pushLockPast(groupId);
        scheduler.scanGroupLock();
    }

    /**
     * 把整支队伍的事件整体挪到「此刻正好落在签到窗口内」，返回新的起始格。
     *
     * <p>窗口边界取<b>规则中心的实时值</b>，不是 BizConstants 的默认常量：管理员把
     * {@code book.check-in-after-min} 改掉之后，合法的签到时刻就跟着变了；用例若还按默认
     * 15 分钟挑格子，会在同一时刻出现「接口说能签、用例说没窗口」的分裂。
     *
     * <p>★ 连预约与 slot 行一起挪：签到窗口按队伍行算，平面图按 slot 行算，
     * 只挪一边会出现「窗口内能签到但座位显示空闲」的自相矛盾现场，用例就成了测 fixture。
     *
     * <p>★ 开放窗口 08:00-22:00（合法格号 16~43）之外没有任何窗口罩得住「此刻」，
     * 这时明确跳过：把队伍挪到 44 格看似能签到，平面图查询会把 slot 夹回 43，
     * 用例就会看到「明明签到了却还显示空闲」——那是现场搭出了产品里不存在的数据。
     *
     * @param groupId 队伍
     * @return 挪动后的起始格
     */
    protected int alignGroupToNow(long groupId) {
        LocalDateTime clock = LocalDateTime.now();
        LocalDate today = clock.toLocalDate();
        int before = ruleConfigService.checkInBeforeMin();
        int after = ruleConfigService.checkInAfterMin();
        int chosen = -1;
        for (int candidate = BizConstants.SLOT_INDEX_MIN; candidate <= BizConstants.SLOT_INDEX_MAX; candidate++) {
            LocalDateTime start = BizConstants.slotStart(today, candidate);
            if (!clock.isBefore(start.minusMinutes(before)) && !clock.isAfter(start.plusMinutes(after))) {
                // 取最后一个（起始时刻最晚的）仍开着的窗口，等价于「此刻这一格」
                chosen = candidate;
            }
        }
        assumeTrue(chosen > 0, "当前时刻 " + clock.toLocalTime() + " 没有任何签到窗口是开着的（规则 "
                + "before=" + before + " / after=" + after + "，开放时段 08:00-22:00），"
                + "跳过而不是把队伍挪到越界时段");
        final int slot = chosen;
        groupRepository.findById(groupId).ifPresent(group -> {
            group.setReserveDate(today);
            group.setStartSlot(slot);
            group.setEndSlot(slot + SLOT_COUNT);
            groupRepository.saveAndFlush(group);
        });
        for (Reservation reservation : reservationsOf(groupId)) {
            reservation.setReserveDate(today);
            reservation.setStartSlot(slot);
            reservation.setEndSlot(slot + SLOT_COUNT);
            reservationRepository.save(reservation);
            jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservation.getId());
            for (int i = slot; i < slot + SLOT_COUNT; i++) {
                jdbc.update("insert into t_reservation_slot (reservation_id, seat_id, reserve_date,"
                                + " slot_index, create_time) values (?,?,?,?,?)",
                        reservation.getId(), reservation.getSeatId(), today, i,
                        java.time.LocalDateTime.now());
            }
        }
        return slot;
    }

    /** 把全队预约的签到时刻推到宽限期之前（验成员未入座缺席用）。 */
    protected void backdateCheckIn(long groupId, int minutes) {
        LocalDateTime at = LocalDateTime.now().minusMinutes(minutes);
        List<Reservation> reservations = reservationsOf(groupId);
        for (Reservation reservation : reservations) {
            reservation.setCheckInTime(at);
        }
        reservationRepository.saveAll(reservations);
    }

    /**
     * 把队伍的开会时刻推到已经过去（验组长错过签到窗口用）。
     *
     * <p>时段取 02:00：既保证「开始 + 宽限」必然已过，又落在开放窗口之外，
     * 不会与其他用例的真实预约撞格。
     */
    protected void backdateGroupStart(long groupId) {
        groupRepository.findById(groupId).ifPresent(group -> {
            group.setReserveDate(LocalDate.now());
            group.setStartSlot(4);
            group.setEndSlot(4 + SLOT_COUNT);
            groupRepository.saveAndFlush(group);
        });
    }

    protected List<Reservation> reservationsOf(long groupId) {
        return reservationRepository.findByGroupId(groupId);
    }

    protected int reservationCount(long groupId) {
        Integer n = jdbc.queryForObject("select count(1) from t_reservation where group_id = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    protected int slotRowCount(long groupId) {
        Integer n = jdbc.queryForObject("select count(1) from t_reservation_slot s "
                + "join t_reservation r on r.id = s.reservation_id where r.group_id = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    /**
     * 清掉本用例留下的占用与桌位。
     *
     * <p>先释放桌上所有预约（含队友「抢先一步」那条），否则座位删不掉、桌也删不掉，
     * 残留座位会改变 1F 研讨协作区的座位数，把与座位数相关的用例挤成偶发失败。
     */
    protected void dropGroupArtifacts(Long groupId, long tableId) throws Exception {
        List<Long> seats = tableSeats(tableId);
        for (Long seatId : seats) {
            for (Long reservationId : jdbc.queryForList(
                    "select id from t_reservation where seat_id = ? and status in ('CONFIRMED','CHECKED_IN')",
                    Long.class, seatId)) {
                release(reservationId, "用例收尾释放");
            }
        }
        if (groupId != null) {
            for (Long reservationId : jdbc.queryForList(
                    "select id from t_reservation where group_id = ?", Long.class, groupId)) {
                release(reservationId, "用例收尾释放");
            }
        }
        for (Long seatId : seats) {
            deleteSeat(seatId);
        }
        exec(delete("/api/staff/tables/" + tableId).header("Authorization", bearer(admin)));
    }

    /** 以「另一个人抢先下单」的等价形态直写一条占用（绕开个人预约在 group_required 分区的 4102）。 */
    protected void takeSeatExternally(long seatId, String username) {
        Long userId = jdbc.queryForObject("select id from t_user where username = ?", Long.class, username);
        LocalDate day = LocalDate.parse(date());
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(userId).seatId(seatId).floorId(floor1).reserveDate(day)
                .startSlot(startSlot()).endSlot(startSlot() + SLOT_COUNT)
                .status(ReservationStatus.CONFIRMED).build());
        for (int i = startSlot(); i < startSlot() + SLOT_COUNT; i++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(reservation).seatId(seatId).reserveDate(day).slotIndex(i).build());
        }
    }

    /** 队伍行的当前快照。 */
    protected Group groupOf(long groupId) {
        return groupRepository.findById(groupId).orElseThrow();
    }
}

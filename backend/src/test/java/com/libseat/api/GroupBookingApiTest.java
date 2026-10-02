package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.GroupRepository;
import com.libseat.scheduler.ReservationScheduler;
import com.libseat.service.GroupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S4 / T-V2-15：队伍模型与按桌下单。
 *
 * <p>这片最需要盯住的是<b>「要么整桌成功，要么一动不动」</b>：锁定是一支队伍的预约第一次以
 * 「一次写多条」的形式落库，部分成功就会留下半张桌 —— 座位既没释放也没人能用。
 * 所以验收 2 断言的不只是错误码，而是回滚后 {@code group_id} 名下一行不剩。
 *
 * <p>★ 用例自建桌位、自清占用：桌位生成会在 1F 研讨协作区真实占行占列，
 * 内存库整个测试 JVM 共享，不清就等于给后面的用例埋座位数相关的偶发失败。
 */
class GroupBookingApiTest extends ApiTestBase {

    /** 分区仅整组预约。 */
    private static final int GROUP_ONLY_ZONE = 4102;

    /** 已锁定，禁止增减成员。 */
    private static final int GROUP_LOCKED = 4104;

    /** 邀请码无效或过期。 */
    private static final int GROUP_INVITE_INVALID = 4105;

    /** 座位已被抢。 */
    private static final int SEAT_CONFLICT = 4091;

    /** 越权（非组长做组长动作）。 */
    private static final int ROLE_NOT_ELIGIBLE = 4031;

    /** 时长 2 小时。 */
    private static final int DURATION_HOURS = 2;

    /** 2 小时 = 4 个 30 分钟格。 */
    private static final int SLOT_COUNT = DURATION_HOURS * BizConstants.SLOTS_PER_HOUR;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupService groupService;

    @Autowired
    private ReservationScheduler scheduler;

    // ==================== 请求与造数工具 ====================

    private long seminarZoneId() throws Exception {
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

    private String date() {
        return dw(1);
    }

    private int startSlot() {
        return slot(9);
    }

    /** 建一张桌并生成 count 个相邻座位，返回桌 ID。 */
    private long newTable(int count) throws Exception {
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
    private List<Long> tableSeats(long tableId) {
        return jdbc.queryForList("select id from t_seat where table_id = ? order by seat_no",
                Long.class, tableId);
    }

    /** 注册一个全新用户，返回 {账号, token}。 */
    private String[] newMember() throws Exception {
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

    private long createGroup(String token, long tableId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", seminarZoneId());
        body.put("tableId", tableId);
        body.put("date", date());
        body.put("startSlot", startSlot());
        body.put("duration", DURATION_HOURS);
        MvcResult result = exec(post("/api/groups")
                .contentType(MediaType.APPLICATION_JSON).content(json(body))
                .header("Authorization", bearer(token)));
        assertEquals(0, code(result), "建队应成功：" + body(result));
        return data(result).path("groupId").asLong();
    }

    private MvcResult invite(String token, long groupId, String username) throws Exception {
        return exec(post("/api/groups/" + groupId + "/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username)))
                .header("Authorization", bearer(token)));
    }

    private MvcResult confirm(String token, long groupId) throws Exception {
        return exec(post("/api/groups/" + groupId + "/confirm").header("Authorization", bearer(token)));
    }

    private JsonNode detail(String token, long groupId) throws Exception {
        return data(exec(get("/api/groups/" + groupId).header("Authorization", bearer(token))));
    }

    /** 把锁定时刻推到当下之前（用例不靠 sleep 等时刻）。 */
    private void pushLockPast(long gid) {
        groupRepository.findById(gid).ifPresent(group -> {
            group.setLockTime(LocalDateTime.now().minusMinutes(1));
            groupRepository.saveAndFlush(group);
        });
    }

    /** 推到点后跑真实扫描链路，验证「到点自动锁定下单」这条触发路径本身。 */
    private void lockNow(long groupId) {
        pushLockPast(groupId);
        scheduler.scanGroupLock();
    }

    private int reservationCount(long groupId) {
        Integer n = jdbc.queryForObject("select count(1) from t_reservation where group_id = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    private int slotRowCount(long groupId) {
        Integer n = jdbc.queryForObject("select count(1) from t_reservation_slot s "
                + "join t_reservation r on r.id = s.reservation_id where r.group_id = ?",
                Integer.class, groupId);
        return n == null ? 0 : n;
    }

    /**
     * 清掉本用例留下的占用与桌位：先强制释放队伍预约（座位才删得掉），再删座位、删桌。
     *
     * @param groupId  队伍（可为 null，表示没建过队）
     * @param tableId  桌位
     */
    private void dropGroupArtifacts(Long groupId, long tableId) throws Exception {
        List<Long> seats = tableSeats(tableId);
        // 先释放桌上所有预约（含队友「抢先一步」那条），否则座位删不掉、桌也删不掉，
        // 残留的座位会改变 1F 研讨协作区的座位数，把座位数相关的用例挤成偶发失败。
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

    /** 以「另一个人抢先下单」的等价形态直写一条占用（避开个人预约被 4102 挡住）。 */
    private void takeSeatExternally(long seatId, String username) {
        Long userId = jdbc.queryForObject("select id from t_user where username = ?", Long.class, username);
        LocalDate date = LocalDate.parse(date());
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(userId).seatId(seatId).floorId(floor1).reserveDate(date)
                .startSlot(startSlot()).endSlot(startSlot() + SLOT_COUNT)
                .status(ReservationStatus.CONFIRMED).build());
        List<ReservationSlot> slots = new ArrayList<>();
        for (int i = startSlot(); i < startSlot() + SLOT_COUNT; i++) {
            slots.add(ReservationSlot.builder().reservation(reservation).seatId(seatId)
                    .reserveDate(date).slotIndex(i).build());
        }
        reservationSlotRepository.saveAllAndFlush(slots);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("【验收1】建队 → 邀请 3 人确认 → 锁定：4 座各一条预约、group_id 相同、slot 行数正确")
    void leaderInviteConfirmLockBooksWholeTable() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        Long groupId = null;
        try {
            groupId = createGroup(leader, tableId);
            JsonNode formed = detail(leader, groupId);
            assertEquals("FORMING", formed.path("status").asText());
            assertEquals(1, formed.path("confirmedCount").asInt(), "组长自己就算一个人");
            assertEquals(BizConstants.GROUP_INVITE_CODE_LENGTH,
                    formed.path("inviteCode").asText().length(), "组长要看得到邀请码");
            assertEquals(4, formed.path("maxMembers").asInt(), "人数上限默认取桌位座位数");

            List<String[]> members = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                members.add(newMember());
            }
            for (String[] member : members) {
                MvcResult invited = invite(leader, groupId, member[0]);
                assertEquals(0, code(invited), "邀请应成功：" + body(invited));
            }
            for (String[] member : members) {
                MvcResult confirmed = confirm(member[1], groupId);
                assertEquals(0, code(confirmed), "成员确认应成功：" + body(confirmed));
            }
            assertTrue(detail(members.get(0)[1], groupId).path("inviteCode").isNull(),
                    "★ 邀请码只回给组长：给谁都等于把队伍变成公开入队入口");
            assertEquals(4, detail(leader, groupId).path("confirmedCount").asInt());

            lockNow(groupId);

            JsonNode locked = detail(leader, groupId);
            assertEquals("RESERVED", locked.path("status").asText(), "锁定后应完成整桌下单");
            assertEquals(4, reservationCount(groupId), "桌上 4 个座位各一条预约，共享同一 group_id");
            assertEquals(4 * SLOT_COUNT, slotRowCount(groupId),
                    "每条预约的 slot 行数必须等于时长格数，少一格就是那一格能被别人重复订");

            List<String> seatNos = new ArrayList<>();
            for (JsonNode member : locked.path("members")) {
                assertTrue(member.path("seatId").asLong() > 0, "成员都要落到具体座位：" + member);
                seatNos.add(member.path("seatNo").asText());
                assertEquals("UNKNOWN", member.path("attendStatus").asText(),
                        "★ 锁定不等于入座：组长签到前没人算 PRESENT");
                assertEquals("RESERVED", displayStatus(floor1, member.path("seatId").asLong(),
                        date(), startSlot()), "平面图必须认得这批占用（组队不新建占用表的意义）");
            }
            assertEquals(4, seatNos.stream().distinct().count(), "4 人必须各占一座，不能两人同座");
            assertEquals(seatNos.stream().sorted().findFirst().orElseThrow(),
                    locked.path("members").get(0).path("seatNo").asText(),
                    "组长分桌头座位：签到与缺席判定都看组长，位置也要一眼认得出");
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }

    @Test
    @DisplayName("【验收2】桌上一座被他人抢走 → 整单回滚，group_id 名下一行不剩")
    void conflictRollsBackWholeOrder() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        String[] mate = newMember();
        Long groupId = null;
        try {
            groupId = createGroup(leader, tableId);
            final long gid = groupId;
            MvcResult invited = invite(leader, gid, mate[0]);
            assertEquals(0, code(invited), "邀请应成功：" + body(invited));
            assertEquals(0, code(confirm(mate[1], gid)));

            // 抢掉「组长会被分到的那一座」（桌内编号最小），冲突必然发生而不是碰运气
            long stolen = tableSeats(tableId).get(0);
            takeSeatExternally(stolen, mate[0]);
            pushLockPast(gid);

            BusinessException thrown = assertThrows(BusinessException.class,
                    () -> groupService.lockAndOrder(gid));
            assertEquals(SEAT_CONFLICT, thrown.getErrorCode().getCode(),
                    "座位冲突必须翻译成 4091，实际：" + thrown.getMessage());

            assertEquals(0, reservationCount(gid),
                    "★ 回滚必须干净：残留一条预约，那半张桌就永远无人能约也无人能用");
            assertEquals(0, slotRowCount(gid), "slot 行同样不得残留");
            assertEquals("FORMING", detail(leader, gid).path("status").asText(),
                    "下单失败后队伍要留在可重试的状态，不能一起烂掉");
            assertEquals(SLOT_COUNT, jdbc.queryForObject(
                            "select count(1) from t_reservation_slot where seat_id = ? and reserve_date = ?",
                            Integer.class, stolen, LocalDate.parse(date())),
                    "别人的占用不能被本事务回滚牵连（回滚只撤销本事务自己写的内容）");
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }

    @Test
    @DisplayName("【验收3】非组长做组长动作 → 4031；锁定后增减成员 → 4104")
    void onlyLeaderActsAndLockedGroupIsFrozen() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        String[] mate = newMember();
        String[] outsider = newMember();
        Long groupId = null;
        try {
            groupId = createGroup(leader, tableId);
            assertEquals(0, code(invite(leader, groupId, mate[0])));
            assertEquals(0, code(confirm(mate[1], groupId)));

            MvcResult memberInvite = invite(mate[1], groupId, outsider[0]);
            assertEquals(ROLE_NOT_ELIGIBLE, code(memberInvite),
                    "成员不能替组长邀请，实际：" + body(memberInvite));
            MvcResult memberCancel = exec(post("/api/groups/" + groupId + "/cancel")
                    .header("Authorization", bearer(mate[1])));
            assertEquals(ROLE_NOT_ELIGIBLE, code(memberCancel),
                    "★ 取消权只在组长身上，成员点取消必须被拒而不是悄悄放行");

            lockNow(groupId);
            assertEquals("RESERVED", detail(leader, groupId).path("status").asText());
            MvcResult lateInvite = invite(leader, groupId, outsider[0]);
            assertEquals(GROUP_LOCKED, code(lateInvite),
                    "已锁定的队伍不能再加人，实际：" + body(lateInvite));
            MvcResult lateQuit = exec(post("/api/groups/" + groupId + "/quit")
                    .header("Authorization", bearer(mate[1])));
            assertEquals(GROUP_LOCKED, code(lateQuit), "已锁定后成员也退不出去");
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }

    @Test
    @DisplayName("【验收4】邀请码无效/过期 → 4105；仅整组预约的分区做个人预约 → 4102")
    void inviteCodeGuardsAndPersonalBookingBlockedInGroupZone() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        String[] mate = newMember();
        Long groupId = null;
        try {
            groupId = createGroup(leader, tableId);
            String inviteCode = detail(leader, groupId).path("inviteCode").asText();

            MvcResult bogus = exec(post("/api/groups/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("inviteCode", "ZZZZZZ")))
                    .header("Authorization", bearer(mate[1])));
            assertEquals(GROUP_INVITE_INVALID, code(bogus), "假码必须 4105，实际：" + body(bogus));

            final long finalGroupId = groupId;
            groupRepository.findById(finalGroupId).ifPresent(group -> {
                group.setInviteExpireTime(LocalDateTime.now().minusMinutes(1));
                groupRepository.saveAndFlush(group);
            });
            MvcResult expired = exec(post("/api/groups/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("inviteCode", inviteCode)))
                    .header("Authorization", bearer(mate[1])));
            assertEquals(GROUP_INVITE_INVALID, code(expired), "过期码也要 4105，实际：" + body(expired));

            // 研讨协作区 group_required=true：个人散约一律服务端拒，前端藏入口不算数
            MvcResult personal = reserveRaw(mate[1], tableSeats(tableId).get(0), date(), startSlot(),
                    DURATION_HOURS);
            assertEquals(GROUP_ONLY_ZONE, code(personal),
                    "仅整组预约的分区必须拦住个人预约，实际：" + body(personal));
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }
}

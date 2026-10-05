package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.config.WebConfig;
import com.libseat.entity.Reservation;
import com.libseat.entity.ReservationSlot;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.ViolationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S4 / T-V2-17：组队前端所依赖的后端口径。
 *
 * <p>页面把签到、取消、邀请、确认都做成了按钮，于是这几条链的口径必须一次验清楚：
 * <ul>
 *   <li><b>个人签到 / 取消在队伍预约上一律拦下</b>：让成员自己签到就绕过了
 *       「组长签到＝全队签到」，让他自己取消就会出现「走了一个人、桌上还占着四格」；</li>
 *   <li><b>NO_SHOW 扫描跳过队伍预约</b>：否则组长错过签到的同一轮里，全桌每人先各记一次爽约，
 *       队伍链再记一次组长缺席 —— 同一件事两套判罚；</li>
 *   <li><b>按钮可用性只认服务端给的 canXxx，且只挂在本人那一行</b>：
 *       confirm 作用的是调用者自己，把按钮画到别人行上改的是自己的状态；</li>
 *   <li><b>建队页要的数字来自服务端</b>：平面图给 tableNo，/config/booking 给组队口径，
 *       前端写死数字的话，改值后页面说明就成了假的。</li>
 * </ul>
 */
class GroupManagedApiTest extends GroupTestSupport {

    @Autowired
    private ViolationRepository violationRepository;

    /** 建队 → 邀请并确认 people-1 人 → 到点锁定下单。 */
    private long readyGroup(String leader, long tableId, int people) throws Exception {
        long groupId = createGroup(leader, tableId);
        for (int i = 1; i < people; i++) {
            String[] member = newMember();
            MvcResult invited = invite(leader, groupId, member[0]);
            assertEquals(0, code(invited), "邀请应成功：" + body(invited));
            MvcResult confirmed = confirm(member[1], groupId);
            assertEquals(0, code(confirmed), "确认应成功：" + body(confirmed));
        }
        lockNow(groupId);
        assertEquals("RESERVED", detail(leader, groupId).path("status").asText(),
                "前置条件：队伍应已完成整桌下单");
        return groupId;
    }

    /**
     * 把队伍与其预约整体挪到凌晨 02:00（开始 + 签到窗口都已过去）。
     *
     * <p>★ 连预约与 slot 行一起挪：NO_SHOW 扫描按预约行算窗口，只挪队伍行等于没挪。
     */
    private void backdateGroupReservations(long groupId) {
        LocalDate today = LocalDate.now();
        groupRepository.findById(groupId).ifPresent(group -> {
            group.setReserveDate(today);
            group.setStartSlot(4);
            group.setEndSlot(4 + SLOT_COUNT);
            groupRepository.saveAndFlush(group);
        });
        for (Reservation reservation : reservationsOf(groupId)) {
            reservation.setReserveDate(today);
            reservation.setStartSlot(4);
            reservation.setEndSlot(4 + SLOT_COUNT);
            reservationRepository.save(reservation);
            jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservation.getId());
            for (int i = 4; i < 4 + SLOT_COUNT; i++) {
                jdbc.update("insert into t_reservation_slot"
                                + " (reservation_id, seat_id, reserve_date, slot_index, create_time)"
                                + " values (?,?,?,?,?)",
                        reservation.getId(), reservation.getSeatId(), today, i, LocalDateTime.now());
            }
        }
    }

    /**
     * 直写一条已过签到窗口的个人预约（对照组）。
     *
     * <p>时段取 02:00：开放窗口是 08:00-22:00，任何真实预约都撞不到这一格，
     * 而扫描器的判定只看「开始 + 签到窗口 &lt; 现在」。
     */
    private long pastDuePersonalReservation(String username) {
        Long userId = jdbc.queryForObject("select id from t_user where username = ?", Long.class, username);
        // 找一个非组队分区的可用座位：那里不会有队伍占用，也不会撞上别人的整桌
        Long seatId = jdbc.queryForObject("select s.id from t_seat s"
                + " join t_zone z on z.id = s.zone_id"
                + " where z.group_required = false and s.status = 'AVAILABLE'"
                + " and s.floor_id = ? order by s.id limit 1", Long.class, floor1);
        LocalDate today = LocalDate.now();
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()
                .userId(userId).seatId(seatId).floorId(floor1).reserveDate(today)
                .startSlot(4).endSlot(4 + SLOT_COUNT)
                .status(ReservationStatus.CONFIRMED).build());
        for (int i = 4; i < 4 + SLOT_COUNT; i++) {
            reservationSlotRepository.saveAndFlush(ReservationSlot.builder()
                    .reservation(reservation).seatId(seatId).reserveDate(today).slotIndex(i).build());
        }
        return reservation.getId();
    }

    private int violationsOf(long userId, ViolationType type) {
        return (int) violationRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(v -> v.getType() == type)
                .count();
    }

    private long userIdOf(String username) {
        return jdbc.queryForObject("select id from t_user where username = ?", Long.class, username);
    }

    @Test
    @DisplayName("【验收1】队伍预约的个人入口：列表给理由、接口拦住，而同一时刻组长签到能成功")
    void individualEntriesYieldToGroup() throws Exception {
        String leader = newUser();
        long tableId = newTable(3);
        try {
            String[] mate = newMember();
            long groupId = createGroup(leader, tableId);
            MvcResult invited = invite(leader, groupId, mate[0]);
            assertEquals(0, code(invited), "邀请应成功：" + body(invited));
            assertEquals(0, code(confirm(mate[1], groupId)), "确认应成功");
            lockNow(groupId);

            // 挪进签到窗口：这样「个人签到被拒」只可能是因为它是队伍预约，而不是因为时间不对
            alignGroupToNow(groupId);
            JsonNode mine = data(exec(get("/api/reservations/my")
                    .param("tab", "upcoming")
                    .header("Authorization", bearer(mate[1]))));
            JsonNode groupItem = null;
            for (JsonNode node : mine.path("list")) {
                if (node.path("groupId").asLong() == groupId) {
                    groupItem = node;
                }
            }
            assertNotNull(groupItem, "★ 队伍下单产生的预约必须出现在成员自己的预约里，否则他不知道自己已经占了座");
            assertFalse(groupItem.path("canCheckIn").asBoolean(),
                    "队伍预约的个人签到按钮必须是灰的：签到归组长");
            assertFalse(groupItem.path("canCancel").asBoolean(),
                    "队伍预约的个人取消按钮必须是灰的：取消归队伍");
            assertTrue(groupItem.path("groupManagedHint").asText().contains("组长"),
                    "置灰要给理由，还要指向正确入口：" + groupItem.path("groupManagedHint").asText());
            assertTrue(groupItem.path("checkInWindowText").asText().contains("组队学习"),
                    "窗口文案也不能再邀请成员点个人签到：" + groupItem.path("checkInWindowText").asText());

            long reservationId = groupItem.path("reservationId").asLong();
            MvcResult checkIn = exec(post("/api/reservations/" + reservationId + "/check-in")
                    .header("Authorization", bearer(mate[1])));
            assertEquals(STATUS_NOT_ALLOWED, code(checkIn),
                    "绕过按钮直接调接口也要拒：" + body(checkIn));
            MvcResult cancel = exec(post("/api/reservations/" + reservationId + "/cancel")
                    .header("Authorization", bearer(mate[1])));
            assertEquals(STATUS_NOT_ALLOWED, code(cancel),
                    "成员不能把自己从桌上摘掉：" + body(cancel));

            // 对照组：同一时刻组长签到成功 → 证明上面的拒绝来自「归队伍管」，不是来自时间窗口
            MvcResult leaderIn = exec(post("/api/groups/" + groupId + "/check-in")
                    .header("Authorization", bearer(leader)));
            assertEquals(0, code(leaderIn), "组长应能在同一时刻完成全队签到：" + body(leaderIn));
            assertEquals("CHECKED_IN", detail(mate[1], groupId).path("status").asText(),
                    "成员看到的队伍状态应随组长签到一起变");
        } finally {
            dropGroupArtifacts(null, tableId);
        }
    }

    @Test
    @DisplayName("【验收2】NO_SHOW 扫描跳过队伍预约：全桌不能被记成 N 次爽约")
    void noShowScanSkipsGroupReservations() throws Exception {
        String leader = newUser();
        long tableId = newTable(3);
        try {
            long groupId = readyGroup(leader, tableId, 3);
            backdateGroupReservations(groupId);

            // 对照：同一轮扫描里，一条普通过期预约必须照常变 NO_SHOW，否则这条用例是空转
            String control = "qc" + Long.toHexString(System.nanoTime());
            MvcResult registered = exec(post("/api/auth/register")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(json(java.util.Map.of(
                            "username", control, "password", "123456", "confirmPassword", "123456",
                            "realName", "对照用户"))));
            assertEquals(0, code(registered), "注册对照账号应成功：" + body(registered));
            long controlId = pastDuePersonalReservation(control);

            scheduler.scanNoShow();

            assertEquals(ReservationStatus.NO_SHOW,
                    reservationRepository.findById(controlId).orElseThrow().getStatus(),
                    "前置条件：扫描器确实处理了过期未签到（对照组）");
            assertEquals(1, violationsOf(userIdOf(control), ViolationType.NO_SHOW),
                    "对照组应记下一次爽约");

            for (Reservation reservation : reservationsOf(groupId)) {
                assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus(),
                        "★ 队伍预约不能被个人 NO_SHOW 抢先处理，组长缺席另有整队取消一条链：reservationId="
                                + reservation.getId());
            }
            List<Long> memberIds = jdbc.queryForList(
                    "select user_id from t_group_member where group_id = ?", Long.class, groupId);
            for (Long id : memberIds) {
                assertEquals(0, violationsOf(id, ViolationType.NO_SHOW),
                        "缺席判罚只能出自队伍链，用户 " + id + " 不该有爽约账");
            }

            jdbc.update("delete from t_reservation_slot where reservation_id = ?", controlId);
            jdbc.update("delete from t_reservation where id = ?", controlId);
        } finally {
            dropGroupArtifacts(null, tableId);
        }
    }

    @Test
    @DisplayName("【验收3】建队页要的数字来自服务端：/config/booking 给出组队口径")
    void bookingConfigCarriesGroupRules() throws Exception {
        JsonNode config = data(exec(get("/api/config/booking")
                .header("Authorization", bearer(admin))));
        assertEquals(BizConstants.GROUP_LOCK_BEFORE_MIN, config.path("groupLockBeforeMin").asInt(),
                "「开始前多少分钟锁定」必须与后端同一份，否则页面提示与实际闸门两套口径");
        assertEquals(BizConstants.GROUP_INVITE_TTL_MIN, config.path("groupInviteTtlMin").asInt());
        assertEquals(BizConstants.GROUP_MIN_MEMBERS, config.path("groupMinMembers").asInt());
        assertEquals(BizConstants.GROUP_ATTEND_GRACE_MIN, config.path("groupAttendGraceMin").asInt());
    }

    @Test
    @DisplayName("【验收4】平面图给整桌选择的数据：桌号 + 本区可否组队")
    void seatMapCarriesTableIdentity() throws Exception {
        long tableId = newTable(4);
        try {
            String tableNo = jdbc.queryForObject("select table_no from t_table where id = ?",
                    String.class, tableId);
            long zoneId = seminarZoneId();
            JsonNode map = data(exec(get("/api/seats/map")
                    .param("floorId", String.valueOf(floor1))
                    .param("zoneId", String.valueOf(zoneId))
                    .param("date", date())
                    .param("slot", String.valueOf(startSlot()))
                    .header("Authorization", bearer(admin))));

            JsonNode rules = map.path("zoneRules");
            assertTrue(rules.path("groupRequired").asBoolean(), "研讨协作区应为仅整组预约");
            assertTrue(rules.path("allowGroup").asBoolean(),
                    "★ 前端要判断「能不能提供选桌入口」，只给 groupRequired 时非强制的组队区就没依据");

            int matched = 0;
            for (JsonNode seat : map.path("seats")) {
                if (seat.path("tableId").asLong() == tableId) {
                    matched++;
                    assertEquals(tableNo, seat.path("tableNo").asText(),
                            "座位必须报出自己的桌号，否则选桌模式只能显示内部 ID");
                }
            }
            assertEquals(4, matched, "本桌 4 个座位都应出现在该分区的平面图里");
        } finally {
            for (Long seatId : tableSeats(tableId)) {
                deleteSeat(seatId);
            }
            exec(delete("/api/staff/tables/" + tableId).header("Authorization", bearer(admin)));
        }
    }

    @Test
    @DisplayName("【验收5】新路由已同步进 SPA 白名单：/groups 与 /groups/create 刷新不白屏")
    void spaWhitelistCoversGroupRoutes() throws Exception {
        GetMapping mapping = WebConfig.SpaFallbackController.class
                .getDeclaredMethod("forward")
                .getAnnotation(GetMapping.class);
        assertNotNull(mapping, "SPA fallback 应当存在");
        List<String> whitelist = Arrays.asList(mapping.value());
        assertTrue(whitelist.contains("/groups"),
                "「我的队伍」刷新要能落到 index.html，实际白名单：" + whitelist);
        assertTrue(whitelist.contains("/groups/create"),
                "「建队页」刷新要能落到 index.html，实际白名单：" + whitelist);
    }

    @Test
    @DisplayName("【验收6】操作位只挂在本人那一行，且锁定后不再给「能邀请」")
    void actionFlagsBelongToSelfAndFollowLock() throws Exception {
        String leader = newUser();
        long tableId = newTable(3);
        try {
            String[] mate = newMember();
            long groupId = createGroup(leader, tableId);
            MvcResult invited = invite(leader, groupId, mate[0]);
            assertEquals(0, code(invited), "邀请应成功：" + body(invited));

            JsonNode asLeader = data(invited);
            JsonNode asMate = detail(mate[1], groupId);
            int selfRows = 0;
            for (JsonNode row : asMate.path("members")) {
                if (row.path("self").asBoolean()) {
                    selfRows++;
                    assertTrue(row.path("canConfirm").asBoolean(),
                            "本人那一行才该拿到可确认位：" + row);
                } else {
                    assertFalse(row.path("canConfirm").asBoolean(),
                            "★ 别人的行给 canConfirm，前端就会把「替别人确认」画成能点的按钮，"
                                    + "而 confirm 作用的是调用者自己：" + row);
                    assertFalse(row.path("canQuit").asBoolean(), row.toString());
                    assertFalse(row.path("canAttend").asBoolean(), row.toString());
                }
            }
            assertEquals(1, selfRows, "成员视图里应恰好有一行是本人");

            assertTrue(asLeader.path("canInvite").asBoolean(), "组队中组长可邀请");
            assertFalse(asLeader.path("canCheckIn").asBoolean(),
                    "还没完成整桌下单（未 RESERVED）时不能签到，理由要说得通："
                            + asLeader.path("checkInDisabledReason").asText());

            // 锁定下单后成员冻结：邀请必须变成灰的并给出理由
            // 人数达标后再锁定，否则队伍会变成「未成团」，签到理由就成了状态而不是窗口
            assertEquals(0, code(confirm(mate[1], groupId)), "成员确认应成功");
            lockNow(groupId);
            JsonNode locked = detail(leader, groupId);
            assertEquals("RESERVED", locked.path("status").asText(),
                    "前置条件：整桌下单应已完成");
            assertFalse(locked.path("canInvite").asBoolean(),
                    "已锁定的队伍不能再加人：" + locked.path("inviteDisabledReason").asText());
            assertTrue(locked.path("inviteDisabledReason").asText().contains("队伍已"),
                    "置灰必须给理由：" + locked.path("inviteDisabledReason").asText());
            // 队伍时段是 dw(1) 的 09:00，签到窗口远未打开 → 状态位必须是 false，
            // 前端才不会出现「按钮能点、接口 4093」
            assertFalse(locked.path("canCheckIn").asBoolean(),
                    "窗口未开时不能签到：" + locked.path("checkInDisabledReason").asText());
            assertTrue(locked.path("checkInDisabledReason").asText().contains("未到签到窗口"),
                    "签到不可用要给时刻理由：" + locked.path("checkInDisabledReason").asText());
        } finally {
            dropGroupArtifacts(null, tableId);
        }
    }

    @Test
    @DisplayName("【验收9】下单一直失败的队伍，过了开会时刻要收尾，不能每分钟空转")
    void expiredGroupStopsRetrying() throws Exception {
        String leader = newUser();
        long tableId = newTable(2);
        try {
            String[] mate = newMember();
            long groupId = createGroup(leader, tableId);
            assertEquals(0, code(invite(leader, groupId, mate[0])), "邀请应成功");
            assertEquals(0, code(confirm(mate[1], groupId)), "确认应成功");
            // 把开会时刻与锁定时刻都推到已经过去：下单必然被「不能预约已过去的时段」长期拦下，
            // 这正是演示库里真实发生过的现场（成员被信用暂停 → 整桌下单每分钟失败一次）
            groupRepository.findById(groupId).ifPresent(group -> {
                group.setReserveDate(LocalDate.now());
                group.setStartSlot(4);
                group.setEndSlot(4 + SLOT_COUNT);
                group.setLockTime(LocalDateTime.now().minusMinutes(1));
                groupRepository.saveAndFlush(group);
            });

            scheduler.scanGroupLock();

            assertEquals("FAILED", groupOf(groupId).getStatus().name(),
                    "★ 过了开会时刻还留在 FORMING，全桌人就一直在等一个再也不会走的倒计时");
            assertEquals(0, reservationCount(groupId), "收尾不该留下半成品预约");

            scheduler.scanGroupLock();
            assertEquals("FAILED", groupOf(groupId).getStatus().name(), "收尾必须幂等，不能被下一轮复活");
        } finally {
            dropGroupArtifacts(null, tableId);
        }
    }
}

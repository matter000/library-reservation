package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.repository.ViolationRepository;
import com.libseat.service.GroupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S4 / T-V2-15：队伍模型与按桌下单。
 *
 * <p>这片最需要盯住的是<b>「要么整桌成功，要么一动不动」</b>：锁定是一支队伍的预约第一次以
 * 「一次写多条」的形式落库，部分成功就会留下半张桌 —— 座位既没释放也没人能用。
 * 所以验收 2 断言的不只是错误码，而是回滚后 {@code group_id} 名下一行不剩。
 */
class GroupBookingApiTest extends GroupTestSupport {

    @Autowired
    private GroupService groupService;

    @Autowired
    private ViolationRepository violationRepository;

    @Test
    @DisplayName("【验收1】建队 → 邀请 3 人确认 → 锁定：4 座各一条预约、group_id 相同、slot 行数正确")
    void leaderInviteConfirmLockBooksWholeTable() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        Long groupId = null;
        try {
            List<String[]> members = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                members.add(newMember());
            }
            groupId = createGroup(leader, tableId);
            JsonNode formed = detail(leader, groupId);
            assertEquals("FORMING", formed.path("status").asText());
            assertEquals(1, formed.path("confirmedCount").asInt(), "组长自己就算一个人");
            assertEquals(BizConstants.GROUP_INVITE_CODE_LENGTH, formed.path("inviteCode").asText().length(),
                    "组长要看得到邀请码");
            assertEquals(4, formed.path("maxMembers").asInt(), "人数上限默认取桌位座位数");

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
            assertEquals(4, reservationCount(groupId), "4 个座位各一条预约，共享同一 group_id");
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
                    "组长分桌头座位：签到与缺席判定都压在组长身上，位置要一眼认得出");
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
            groupId = createConfirmedGroup(leader, tableId, List.<String[]>of(mate));
            final long gid = groupId;

            // 抢掉「组长会被分到的那一座」（桌内编号最小），让冲突必然发生而不是碰运气
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
            groupId = createConfirmedGroup(leader, tableId, List.<String[]>of(mate));

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
            final long gid = groupId;
            String inviteCode = detail(leader, gid).path("inviteCode").asText();

            MvcResult bogus = exec(post("/api/groups/join")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("inviteCode", "ZZZZZZ")))
                    .header("Authorization", bearer(mate[1])));
            assertEquals(GROUP_INVITE_INVALID, code(bogus), "假码必须 4105，实际：" + body(bogus));

            groupRepository.findById(gid).ifPresent(group -> {
                group.setInviteExpireTime(java.time.LocalDateTime.now().minusMinutes(1));
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

    @Test
    @DisplayName("【补充】人数不足到锁定时刻 → FAILED，且不占任何座位")
    void insufficientMembersFailWithoutBooking() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        Long groupId = null;
        try {
            groupId = createGroup(leader, tableId);
            JsonNode view = detail(leader, groupId);
            assertEquals(2, view.path("minMembers").asInt(), "默认 2 人成团");
            assertEquals(1, view.path("confirmedCount").asInt(), "现在只有组长自己");

            lockNow(groupId);

            assertEquals("FAILED", detail(leader, groupId).path("status").asText());
            assertEquals(0, reservationCount(groupId), "★ 未成团不能占座，否则桌位被空队锁死");
            assertEquals(0, slotRowCount(groupId));
            // 未成团不算违规：没人爽约，只是没凑够人
            assertEquals(0, violationRepository.findByUserIdOrderByIdDesc(uid(leader)).size(),
                    "未成团不该给任何人生成违规");
            // ★ B10：没凑够人这件事只有组长自己看得见界面，必须留一条能看懂原因的通知
            List<String> failedReason = noticeContentsToLeader(leader, groupId);
            assertEquals(1, failedReason.size(), "未成团必须给组长一条解释，实际：" + failedReason);
            assertTrue(failedReason.get(0).contains("最少"),
                    "正文要写清是人数不足（确认了几人、最少要几人）：" + failedReason.get(0));
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }

    @Test
    @DisplayName("【B10】下单失败走过调度器：组长收到一条写明原因的通知，反复重试不翻倍")
    void lockFailureNotifiesLeaderExactlyOnce() throws Exception {
        long tableId = newTable(4);
        String leader = newUser();
        String[] mate = newMember();
        Long groupId = null;
        try {
            groupId = createConfirmedGroup(leader, tableId, List.<String[]>of(mate));
            final long gid = groupId;
            long stolen = tableSeats(tableId).get(0);
            takeSeatExternally(stolen, mate[0]);
            pushLockPast(gid);

            scheduler.scanGroupLock();

            assertEquals("FORMING", detail(leader, gid).path("status").asText(),
                    "座位冲突后队伍要留在可重试状态");
            List<String> reasons = noticeContentsToLeader(leader, gid);
            assertEquals(1, reasons.size(),
                    "★ 组长必须知道自己为什么没订上 —— 只有日志不算交付：" + reasons);
            assertTrue(reasons.get(0).contains("他人预约") || reasons.get(0).contains("冲突"),
                    "通知要写清失败原因（座位被抢），而不是只说「没订上」：" + reasons.get(0));

            // 调度器每分钟一跳，失败原因也一样：重试可以有，骚扰不可以有
            scheduler.scanGroupLock();
            scheduler.scanGroupLock();
            assertEquals(1, jdbc.queryForObject(
                            "select count(1) from t_notice where user_id = ? and type = ?"
                                    + " and ref_type = 'GROUP' and ref_id = ?",
                            Integer.class, uid(leader), "GROUP_ORDER_FAILED", gid),
                    "同一队伍同一类失败只能有一条（去重键是 谁+哪类+指向哪条业务记录）");
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }

    /** 组长名下、指向这支队伍「未成团」通知的正文列表。 */
    private List<String> noticeContentsToLeader(String leaderToken, long groupId) throws Exception {
        return jdbc.queryForList(
                "select content from t_notice where user_id = ? and type = ?"
                        + " and ref_type = 'GROUP' and ref_id = ?",
                String.class, uid(leaderToken), "GROUP_ORDER_FAILED", groupId);
    }

    @Test
    @DisplayName("【B5】一组最多 6 人：8 座桌省略上限按 6 收，显式 9 人 4001，满员后第 7 个邀请被拒")
    void groupSizeCappedAtSixMembers() throws Exception {
        long tableId = newTable(8);
        String leader = newUser();
        Long groupId = null;
        try {
            MvcResult tooMany = createGroupRaw(leader, tableId, 9, null);
            assertEquals(PARAM_ERROR, code(tooMany), "9 人应被拦下，实际：" + body(tooMany));
            assertTrue(msg(tooMany).contains("6"), "回执必须说明上限是 6 人，实际：" + msg(tooMany));

            // ★ 上下限要一起成立：省略上限被夹到 6 之后，「成团人数 7」就不能再被放过
            MvcResult minAboveCap = createGroupRaw(leader, tableId, null, 7);
            assertEquals(PARAM_ERROR, code(minAboveCap),
                    "成团人数超过夹后上限应 4001，实际：" + body(minAboveCap));

            groupId = createGroup(leader, tableId);
            JsonNode formed = detail(leader, groupId);
            assertEquals(BizConstants.GROUP_MAX_MEMBERS, formed.path("maxMembers").asInt(),
                    "★ 省略上限取 min(本桌座位数, 6)：桌子多大不决定一队人数，产品口径才决定"
                            + "（桌位 seatCount 允许到 12，只靠家具尺寸约束等于放弃 PRD §12-6/16）");

            for (int i = 0; i < BizConstants.GROUP_MAX_MEMBERS - 1; i++) {
                MvcResult invited = invite(leader, groupId, newMember()[0]);
                assertEquals(0, code(invited), "第 " + (i + 2) + " 人应能入队：" + body(invited));
            }
            MvcResult seventh = invite(leader, groupId, newMember()[0]);
            assertEquals(STATUS_NOT_ALLOWED, code(seventh),
                    "满 6 人后第 7 个邀请必须被拒，实际：" + body(seventh));
            assertTrue(msg(seventh).contains("6"), "要给出上限数字，实际：" + msg(seventh));
        } finally {
            dropGroupArtifacts(groupId, tableId);
        }
    }
}

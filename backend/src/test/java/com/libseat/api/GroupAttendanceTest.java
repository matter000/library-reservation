package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.entity.User;
import com.libseat.entity.Violation;
import com.libseat.enums.ViolationType;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ViolationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S4 / T-V2-16：组长签到与缺席处理。
 *
 * <p>三条口径各自守着一个不同的事故，所以分开断言：
 * <ul>
 *   <li><b>组长签到 = 全队签到，但不等于全员到场</b>：成员入座必须本人确认，
 *       否则一次签到就给全桌做了不在场证明；</li>
 *   <li><b>成员缺席不释放座位</b>（PRD §7.2）：整桌是一个使用单元，删 slot 会变成
 *       「四人桌只有三人能用」而没人知道为什么；</li>
 *   <li><b>组长缺席整队取消，成员不记违规</b>：队伍没开起来不是成员的责任。</li>
 * </ul>
 */
class GroupAttendanceTest extends GroupTestSupport {

    @Autowired
    private ViolationRepository violationRepository;

    @Autowired
    private UserRepository userRepository;

    /** 一支已锁定、可签到的队伍现场。 */
    private static final class Crew {

        private long groupId;
        private long tableId;
        private String leader;
        private final List<String[]> members = new ArrayList<>();

        private String token(int index) {
            return members.get(index)[1];
        }

        private long userId(int index) {
            return Long.parseLong(members.get(index)[2]);
        }
    }

    private int score(long userId) {
        return userRepository.findById(userId).map(User::getCreditScore).orElse(-1);
    }

    private List<Violation> violationsOf(long userId) {
        return violationRepository.findByUserIdOrderByIdDesc(userId);
    }

    /** 建桌 → 建队 → 邀请并确认 → 锁定下单，返回就绪的队伍。 */
    private Crew readyCrew(int people) throws Exception {
        Crew crew = new Crew();
        crew.tableId = newTable(people);
        crew.leader = newUser();
        for (int i = 1; i < people; i++) {
            String[] member = newMember();
            member = new String[]{member[0], member[1], String.valueOf(uid(member[1]))};
            crew.members.add(member);
        }
        crew.groupId = createGroup(crew.leader, crew.tableId);
        for (String[] member : crew.members) {
            MvcResult invited = invite(crew.leader, crew.groupId, member[0]);
            assertEquals(0, code(invited), "邀请应成功：" + body(invited));
            MvcResult confirmed = confirm(member[1], crew.groupId);
            assertEquals(0, code(confirmed), "确认应成功：" + body(confirmed));
        }
        lockNow(crew.groupId);
        assertEquals("RESERVED", detail(crew.leader, crew.groupId).path("status").asText(),
                "前置条件：队伍应已完成整桌下单");
        return crew;
    }

    /** 把开会时刻挪到签到窗口内并组长签到，返回挪动后的起始格。 */
    private int leaderCheckIn(Crew crew) throws Exception {
        int slot = alignGroupToNow(crew.groupId);
        MvcResult result = exec(post("/api/groups/" + crew.groupId + "/check-in")
                .header("Authorization", bearer(crew.leader)));
        assertEquals(0, code(result), "组长签到应成功：" + body(result));
        return slot;
    }

    private MvcResult attend(String token, long groupId) throws Exception {
        return exec(post("/api/groups/" + groupId + "/attend").header("Authorization", bearer(token)));
    }

    @Test
    @DisplayName("【验收1】组长签到 → 4 条预约同时 CHECKED_IN，平面图 4 座一起变红；成员入座要本人确认")
    void leaderCheckInTurnsWholeTableRed() throws Exception {
        Crew crew = readyCrew(4);
        try {
            // 签到前：成员不能先确认入座，非组长也不能替组长签到
            assertEquals(STATUS_NOT_ALLOWED, code(attend(crew.token(0), crew.groupId)),
                    "组长签到之前成员确认入座必须被拒");
            assertEquals(ROLE_NOT_ELIGIBLE,
                    code(exec(post("/api/groups/" + crew.groupId + "/check-in")
                            .header("Authorization", bearer(crew.token(0))))),
                    "签到是组长的动作，成员做了得 4031");

            int slot = leaderCheckIn(crew);

            assertEquals("CHECKED_IN", detail(crew.leader, crew.groupId).path("status").asText());
            String today = LocalDate.now().toString();
            List<Long> seats = tableSeats(crew.tableId);
            assertEquals(4, seats.size());
            int checkedIn = jdbc.queryForObject(
                    "select count(1) from t_reservation where group_id = ? and status = 'CHECKED_IN'"
                            + " and check_in_time is not null", Integer.class, crew.groupId);
            assertEquals(4, checkedIn, "全队 4 条预约都要翻成 CHECKED_IN");
            for (Long seatId : seats) {
                assertEquals("IN_USE", displayStatus(floor1, seatId, today, slot),
                        "★ 平面图上 4 座必须同时变红：签到是整桌一起生效的，一座一格不一致就是派生漏了");
            }
            JsonNode afterCheckIn = detail(crew.leader, crew.groupId);
            for (JsonNode member : afterCheckIn.path("members")) {
                assertEquals("UNKNOWN", member.path("attendStatus").asText(),
                        "组长签到不能替成员写成已入座");
            }

            MvcResult attended = attend(crew.token(0), crew.groupId);
            assertEquals(0, code(attended), "本人确认入座应成功：" + body(attended));
            String seatNo = null;
            for (JsonNode member : data(attended).path("members")) {
                if ("PRESENT".equals(member.path("attendStatus").asText())) {
                    seatNo = member.path("seatNo").asText();
                }
            }
            assertNotNull(seatNo, "确认的人应变 PRESENT，其余保持 UNKNOWN");
            assertEquals(1, jdbc.queryForObject("select count(1) from t_group_member "
                    + "where group_id = ? and attend_status = 'PRESENT'", Integer.class, crew.groupId));
            assertEquals(STATUS_NOT_ALLOWED, code(attend(crew.token(0), crew.groupId)),
                    "重复确认入座应 4093");
        } finally {
            dropGroupArtifacts(crew.groupId, crew.tableId);
        }
    }

    @Test
    @DisplayName("【验收2】成员过宽限未入座 → 记 ABSENT + 违规，但座位仍为占用态（不释放）")
    void absentMemberIsViolatedButSeatStaysOccupied() throws Exception {
        Crew crew = readyCrew(4);
        try {
            int slot = leaderCheckIn(crew);
            // 只有第 2 个成员到场，其余两人留给缺席判定
            assertEquals(0, code(attend(crew.token(1), crew.groupId)));
            backdateCheckIn(crew.groupId, GRACE_MIN + 5);
            scheduler.scanGroupAttendance();

            long absentId = crew.userId(0);
            long presentId = crew.userId(1);
            List<Violation> absentViolations = violationsOf(absentId);
            assertEquals(1, absentViolations.size(), "缺席应只记一条违规");
            assertEquals(ViolationType.GROUP_ABSENT, absentViolations.get(0).getType());
            assertEquals(-10, absentViolations.get(0).getScoreDelta().intValue());
            assertTrue(absentViolations.get(0).getEvidence().contains("group.attend-grace-min"),
                    "证据要写明按哪条规则裁的：" + absentViolations.get(0).getEvidence());
            assertEquals(90, score(absentId), "缺席按 −10 扣分");

            assertEquals(0, violationsOf(presentId).size(), "到场的人不该有任何违规");
            assertEquals(100, score(presentId));

            // ★ 关键：缺席者的座位不能被释放
            String today = LocalDate.now().toString();
            List<Long> seats = tableSeats(crew.tableId);
            assertEquals(4, seats.size());
            for (Long seatId : seats) {
                assertEquals("IN_USE", displayStatus(floor1, seatId, today, slot),
                        "缺席成员的座位要留给队伍（整桌是一个使用单元），不能被扫描顺手放掉");
            }
            assertEquals(4 * SLOT_COUNT, slotRowCount(crew.groupId), "slot 行一条都不能少");
            for (JsonNode member : detail(crew.leader, crew.groupId).path("members")) {
                if ("MEMBER".equals(member.path("memberRole").asText())
                        && member.path("userId").asLong() == absentId) {
                    assertEquals("ABSENT", member.path("attendStatus").asText());
                }
            }
        } finally {
            dropGroupArtifacts(crew.groupId, crew.tableId);
        }
    }

    @Test
    @DisplayName("【验收3】组长未签到 → 整队取消并清空 slot，组长 −20，成员不记违规")
    void leaderAbsenceCancelsWholeGroup() throws Exception {
        Crew crew = readyCrew(4);
        long leaderId = uid(crew.leader);
        try {
            backdateGroupStart(crew.groupId);
            scheduler.scanGroupAttendance();

            assertEquals("CANCELLED", detail(crew.leader, crew.groupId).path("status").asText());
            assertEquals(0, slotRowCount(crew.groupId), "★ 整队取消必须把占用全放掉，剩一格就是幽灵占座");
            assertEquals(4, jdbc.queryForObject(
                    "select count(1) from t_reservation where group_id = ? and status = 'CANCELLED'",
                    Integer.class, crew.groupId));
            for (Long seatId : tableSeats(crew.tableId)) {
                assertEquals("FREE", displayStatus(floor1, seatId, date(), startSlot()),
                        "座位必须真的回到平面图，光改预约状态不算释放");
            }

            List<Violation> leaderViolations = violationsOf(leaderId);
            assertEquals(1, leaderViolations.size(), "只处罚组长一人");
            assertEquals(ViolationType.LEADER_ABSENT, leaderViolations.get(0).getType());
            assertEquals(-20, leaderViolations.get(0).getScoreDelta().intValue());
            assertTrue(leaderViolations.get(0).getEvidence().contains("cancelledReservations"),
                    "证据要能说明影响面：" + leaderViolations.get(0).getEvidence());
            assertEquals(80, score(leaderId), "组长 −20");
            for (int i = 0; i < 3; i++) {
                assertEquals(0, violationsOf(crew.userId(i)).size(), "成员不该替组长背锅");
                assertEquals(100, score(crew.userId(i)));
            }
        } finally {
            dropGroupArtifacts(crew.groupId, crew.tableId);
        }
    }

    @Test
    @DisplayName("【验收4】扫描幂等：重复执行不重复扣分、不重复取消")
    void scansAreIdempotent() throws Exception {
        Crew absentCrew = readyCrew(3);
        Crew leaderCrew = null;
        try {
            leaderCheckIn(absentCrew);
            backdateCheckIn(absentCrew.groupId, GRACE_MIN + 5);
            scheduler.scanGroupAttendance();
            long absentId = absentCrew.userId(0);
            assertEquals(1, violationsOf(absentId).size(), "第一次扫描记一条违规");
            assertEquals(90, score(absentId));

            scheduler.scanGroupAttendance();
            scheduler.scanGroupAttendance();
            assertEquals(1, violationsOf(absentId).size(), "★ 重复扫描不得重复扣分");
            assertEquals(90, score(absentId), "分数也不能被扣第二次");

            leaderCrew = readyCrew(2);
            long leaderId = uid(leaderCrew.leader);
            backdateGroupStart(leaderCrew.groupId);
            scheduler.scanGroupAttendance();
            assertEquals(1, violationsOf(leaderId).size());
            assertEquals(80, score(leaderId));
            int cancelled = jdbc.queryForObject("select count(1) from t_reservation where group_id = ?"
                    + " and status = 'CANCELLED'", Integer.class, leaderCrew.groupId);
            assertEquals(2, cancelled);

            scheduler.scanGroupAttendance();
            assertEquals(1, violationsOf(leaderId).size(), "整队取消也不能被扫第二次再扣一遍");
            assertEquals(80, score(leaderId));
        } finally {
            dropGroupArtifacts(absentCrew.groupId, absentCrew.tableId);
            if (leaderCrew != null) {
                dropGroupArtifacts(leaderCrew.groupId, leaderCrew.tableId);
            }
        }
    }
}

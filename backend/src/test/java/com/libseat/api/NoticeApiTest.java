package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.common.BizConstants;
import com.libseat.entity.Group;
import com.libseat.entity.GroupMember;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.entity.Waitlist;
import com.libseat.enums.AttendStatus;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.InviteStatus;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.NoticeType;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.enums.WaitlistStatus;
import com.libseat.repository.GroupMemberRepository;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.WaitlistRepository;
import com.libseat.scheduler.ReminderTask;
import com.libseat.service.CreditService;
import com.libseat.service.GroupStateService;
import com.libseat.service.WaitlistStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S5 / T-V2-20：站内通知中心。
 *
 * <p>验收要守住的三件事：
 * <ol>
 *   <li><b>三类通知都要产生且可读、可标已读、未读数正确</b>：候补 offer、暂离到期、违规即时；</li>
 *   <li><b>同一条不得重复生成</b>：提醒任务每分钟重跑，去重靠 {@code uk_notice_ref} +
 *       服务里的存在性检查，而不是靠「记得上次跑到哪儿」；</li>
 *   <li><b>通知只是提醒，不是判定</b>：本系统没有第三方推送通道，「推送」= 站内通知 + 轮询。
 *       所以用例断言的是「该打扰时打扰一次」，从不把消息当成状态来源。</li>
 * </ol>
 *
 * <p>★ 提醒类用例通过 {@link ReminderTask#sendReminders(LocalDateTime)} 交出判定时刻：
 * 逻辑一行没改，但用例不再依赖「现在几点」，也不会在夜里跑回归时静默跳过。
 */
class NoticeApiTest extends ApiTestBase {

    /** 未登录。 */
    private static final int UNAUTHORIZED = 4010;

    /** 资源不存在（别人的通知按不存在回）。 */
    private static final int NOT_FOUND = 4040;

    /** 用例时段（明天 09:00）。 */
    private static final int START = slot(9);

    @Autowired
    private CreditService creditService;

    @Autowired
    private ReminderTask reminderTask;

    @Autowired
    private WaitlistStateService waitlistStateService;

    @Autowired
    private GroupStateService groupStateService;

    @Autowired
    private WaitlistRepository waitlistRepository;

    @Autowired
    private LeaveRecordRepository leaveRecordRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    /** 本用例造的现场（预约 / 队伍），收尾逐一删掉，不给共享内存库留脏数据。 */
    private final List<Long> createdReservations = new ArrayList<>();

    private final List<Long> createdGroups = new ArrayList<>();

    private final List<Long> createdWaitlists = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long reservationId : createdReservations) {
            jdbc.update("delete from t_leave_record where reservation_id = ?", reservationId);
            jdbc.update("delete from t_reservation_slot where reservation_id = ?", reservationId);
            jdbc.update("delete from t_reservation where id = ?", reservationId);
        }
        createdReservations.clear();
        for (Long groupId : createdGroups) {
            jdbc.update("delete from t_group_member where group_id = ?", groupId);
            jdbc.update("delete from t_group where id = ?", groupId);
        }
        createdGroups.clear();
        // ★ 用例造的候补行是 OFFERED 且带真实截止时刻，留在共享内存库里会被别的类的
        //   过期扫描选中（WaitlistApiTest 的计数断言就是这样被串到的）。必须自己收走。
        for (Long waitlistId : createdWaitlists) {
            jdbc.update("delete from t_waitlist where id = ?", waitlistId);
        }
        createdWaitlists.clear();
    }

    // ==================== 验收 1：三类通知都产生 ====================

    @Test
    @DisplayName("【验收1】违规即时通知：正文自带扣分、余额与申诉时限，未读数 +1")
    void violationProducesReadableNotice() throws Exception {
        String token = newUser();
        long userId = uid(token);
        assertEquals(0, unread(notices(token)), "前置条件：新账号不该已经有通知");

        creditService.applyViolation(userId, ViolationType.NO_SHOW, null,
                "{\"rule\":\"book.check-in-after-min\"}", null, null);

        JsonNode notice = only(token, "VIOLATION");
        assertTrue(notice.path("content").asText().contains("扣 10 分"),
                "要说清扣了多少：" + notice.path("content").asText());
        assertTrue(notice.path("content").asText().contains("24 小时"),
                "要给出申诉时限，否则用户不知道现在还有救：" + notice.path("content").asText());
        assertEquals("/credit", notice.path("link").asText(), "链接要直达申诉入口，而不是让用户自己找");
        assertEquals(Boolean.FALSE, notice.path("readFlag").asBoolean());
        assertEquals(1, unread(notices(token)));
        assertEquals(NoticeType.VIOLATION.getText(), notice.path("typeText").asText());
    }

    @Test
    @DisplayName("【验收1】候补顶位 → 队首收到 offer 通知；同一条不重复生成")
    void waitlistOfferNotifiesHeadOnlyOnce() throws Exception {
        String token = newUser();
        long userId = uid(token);
        LocalDate day = LocalDate.parse(dw(1));
        long zoneId = jdbc.queryForObject("select id from t_zone where code = '1F-SILENT'", Long.class);
        Waitlist entry = Waitlist.builder()
                .userId(userId)
                .floorId(floor1)
                .zoneId(zoneId)
                .reserveDate(day)
                .startSlot(START)
                .endSlot(START + 2)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        // 状态要在首次 insert 之前定：status 是 NOT NULL，先插再改会撞约束
        entry.changeStatus(WaitlistStatus.WAITING);
        waitlistRepository.saveAndFlush(entry);
        createdWaitlists.add(entry.getId());

        LocalDateTime now = LocalDateTime.now();
        assertTrue(waitlistStateService.onSeatReleased(zoneId, day, START, START + 2, now),
                "前置条件：释放应顶位");

        JsonNode notice = only(token, "WAITLIST_OFFER");
        String content = notice.path("content").asText();
        assertTrue(content.contains("09:00-10:00"), "要说清是哪一段时间：" + content);
        assertTrue(content.contains("不记违规"), "要说明超时不确认不罚：" + content);
        assertTrue(content.contains("不是留座") || content.contains("可能被别人直接约走"),
                "★ 必须讲清候补不留座，否则用户会以为位置已经是他的了：" + content);
        assertEquals(NoticeType.WAITLIST_OFFER.getText(), notice.path("typeText").asText());
        assertTrue(notice.path("expireTime").asText().length() > 0, "带行动窗口截止时刻");
        assertTrue(!notice.path("expired").asBoolean(), "刚发出的 offer 不该显示已过期");
        assertEquals("/my/waitlist", notice.path("link").asText());

        // 幂等：同一（人, 类型, 业务对象）再写一次不应多出一条
        waitlistStateService.onSeatReleased(zoneId, day, START, START + 2, now);
        waitlistStateService.onSeatReleased(zoneId, day, START, START + 2, now);
        assertEquals(1, countOfType(notices(token), "WAITLIST_OFFER"), "重复顶位不得重复打扰");
    }

    @Test
    @DisplayName("【验收1】暂离到期提醒：剩 5 分钟内发一次，重跑不重发")
    void leaveExpiryRemindsOnce() throws Exception {
        String token = newUser();
        long userId = uid(token);
        String date = dw(1);
        long seat = freeSeat(date, START);
        long reservationId = reserveOk(token, seat, date, START, 1).path("reservationId").asLong();
        createdReservations.add(reservationId);

        // 现场造到「使用中 + 已登记暂离」：签到窗口按真实时钟走，用例无法在任何时刻都赶上它，
        // 而这里要测的是提醒任务本身，不是签到链（签到链由 LeaveApiTest 覆盖）。
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservationRepository.saveAndFlush(reservation);
        // 时刻取整秒：DATETIME 存不下纳秒，180 秒被截成 179.6 秒会让「还剩 3 分钟」显示成 2 分钟
        LocalDateTime when = LocalDateTime.now().withNano(0);
        leaveRecordRepository.saveAndFlush(LeaveRecord.builder()
                .reservationId(reservationId)
                .userId(userId)
                .seatId(seat)
                .reasonCode("MEAL")
                .startTime(when.minusMinutes(27))
                .plannedEndTime(when.plusMinutes(3))
                .status(LeaveStatus.OPEN)
                .openFlag(LeaveStatus.OPEN_FLAG)
                .createTime(when.minusMinutes(27))
                .updateTime(when)
                .build());

        reminderTask.sendReminders(when);
        JsonNode notice = only(token, "LEAVE_EXPIRE");
        assertTrue(notice.path("content").asText().contains("还剩 3 分钟"),
                "要给出剩余时间，用户才知道来不来得及：" + notice.path("content").asText());
        assertTrue(notice.path("content").asText().contains("不顺延"),
                "★ 必须提醒剩余使用时刻不变，这是超时后争执的源头：" + notice.path("content").asText());

        reminderTask.sendReminders(when.plusMinutes(1));
        reminderTask.sendReminders(when.plusMinutes(2));
        assertEquals(1, countOfType(notices(token), "LEAVE_EXPIRE"),
                "同一暂离记录每分钟都在窗口内，但只该打扰一次");
    }

    @Test
    @DisplayName("【验收1】签到提醒：窗口未开不发，开了发一次，过了截止不再发")
    void checkInReminderRespectsWindow() throws Exception {
        String token = newUser();
        String date = dw(1);
        long seat = freeSeat(date, START);
        long reservationId = reserveOk(token, seat, date, START, 1).path("reservationId").asLong();
        createdReservations.add(reservationId);
        LocalDateTime start = BizConstants.slotStart(LocalDate.parse(date), START);

        reminderTask.sendReminders(start.minusMinutes(20));
        assertEquals(0, countOfType(notices(token), "CHECK_IN_REMIND"),
                "提前 15 分钟才开窗口，未到点不该打扰");

        reminderTask.sendReminders(start.minusMinutes(5));
        JsonNode notice = only(token, "CHECK_IN_REMIND");
        assertTrue(notice.path("content").asText().contains("现在可以签到"),
                "文案要说明窗口已开：" + notice.path("content").asText());
        assertTrue(notice.path("content").asText().contains("爽约"), "要说清不来的后果：" + notice.path("content").asText());

        reminderTask.sendReminders(start.plusMinutes(30));
        assertEquals(1, countOfType(notices(token), "CHECK_IN_REMIND"),
                "签到截止之后不再补发（那时已经记爽约了）");
    }

    // ==================== 验收 2：读、标已读、边界 ====================

    @Test
    @DisplayName("【验收2】列表只回本人通知，标已读后未读数下降；越权按 4040、未登录按 4010")
    void listAndMarkReadAreScopedToOwner() throws Exception {
        String owner = newUser();
        String stranger = newUser();
        creditService.applyViolation(uid(owner), ViolationType.NO_SHOW, null,
                "{\"rule\":\"book.check-in-after-min\"}", null, null);
        creditService.applyViolation(uid(stranger), ViolationType.NO_SHOW, null,
                "{\"rule\":\"book.check-in-after-min\"}", null, null);

        JsonNode list = notices(owner);
        assertEquals(1, list.path("list").size(), "只应看到自己那一条");
        assertEquals(1, list.path("unread").asLong());
        long noticeId = list.path("list").get(0).path("noticeId").asLong();

        MvcResult cross = exec(post("/api/me/notices/" + noticeId + "/read")
                .header("Authorization", bearer(stranger)));
        assertEquals(NOT_FOUND, code(cross), "★ 别人的通知不能标已读：" + body(cross));
        assertEquals(1, notices(owner).path("unread").asLong(), "越权请求不得改动他人通知");

        MvcResult read = exec(post("/api/me/notices/" + noticeId + "/read")
                .header("Authorization", bearer(owner)));
        assertEquals(0, code(read), "标已读应成功：" + body(read));
        assertTrue(data(read).path("readFlag").asBoolean());
        assertEquals(0, notices(owner).path("unread").asLong(), "未读数应随之下降");

        MvcResult anonymous = exec(get("/api/me/notices"));
        assertEquals(UNAUTHORIZED, code(anonymous), "未登录不得读别人的通知列表");
    }

    // ==================== 组队：缺席取消要通知成员 ====================

    @Test
    @DisplayName("【验收1】组长未签到整队取消：组长与已确认成员各一条，婉拒者不收")
    void leaderAbsenceNotifiesConfirmedMembers() throws Exception {
        String leader = newUser();
        String mate = newUser();
        String declined = newUser();
        LocalDate day = LocalDate.parse(dw(1));
        Group group = groupRepository.saveAndFlush(Group.builder()
                .leaderId(uid(leader))
                .floorId(floor1)
                .zoneId(jdbc.queryForObject("select id from t_zone where code = '1F-SILENT'", Long.class))
                .reserveDate(day)
                .startSlot(START)
                .endSlot(START + 2)
                .minMembers(2)
                .maxMembers(4)
                .lockTime(LocalDateTime.now().minusMinutes(1))
                .status(GroupStatus.RESERVED)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
        createdGroups.add(group.getId());
        member(group.getId(), uid(leader), "LEADER", InviteStatus.CONFIRMED);
        member(group.getId(), uid(mate), "MEMBER", InviteStatus.CONFIRMED);
        member(group.getId(), uid(declined), "MEMBER", InviteStatus.DECLINED);

        groupStateService.cancelForLeaderAbsence(group, LocalDateTime.now());

        JsonNode leaderNotice = only(leader, "GROUP_LEADER_ABSENT");
        assertTrue(leaderNotice.path("content").asText().contains("只处罚组长"),
                "组长那条要说清处罚落在谁头上：" + leaderNotice.path("content").asText());
        JsonNode mateNotice = only(mate, "GROUP_LEADER_ABSENT");
        assertTrue(mateNotice.path("content").asText().contains("你不记任何违规"),
                "成员不该以为自己被扣分：" + mateNotice.path("content").asText());
        assertEquals(0, countOfType(notices(declined), "GROUP_LEADER_ABSENT"),
                "★ 婉拒过的人不在这队里，不该收到「你的队伍取消了」");
    }

    // ==================== 工具 ====================

    private void member(Long groupId, Long userId, String role, InviteStatus status) {
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .groupId(groupId)
                .userId(userId)
                .memberRole(role)
                .inviteStatus(status)
                .attendStatus(AttendStatus.UNKNOWN)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    /** 1F 静音区里指定时段空闲的座位（沿用既有约定：逐格取交集，不自己数 slot 行）。 */
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

    private JsonNode notices(String token) throws Exception {
        return data(exec(get("/api/me/notices").header("Authorization", bearer(token))));
    }

    private long unread(JsonNode list) {
        return list.path("unread").asLong();
    }

    private int countOfType(JsonNode list, String type) {
        int count = 0;
        for (JsonNode node : list.path("list")) {
            if (type.equals(node.path("type").asText())) {
                count++;
            }
        }
        return count;
    }

    /** 取某人某类型的唯一一条通知（条数不为 1 直接失败，重复生成就是 bug）。 */
    private JsonNode only(String token, String type) throws Exception {
        JsonNode list = notices(token);
        JsonNode hit = null;
        int count = 0;
        for (JsonNode node : list.path("list")) {
            if (type.equals(node.path("type").asText())) {
                count++;
                hit = node;
            }
        }
        assertEquals(1, count, type + " 通知应恰好一条，实际 " + count);
        return hit;
    }

    private long uid(String token) throws Exception {
        return data(exec(get("/api/auth/me").header("Authorization", bearer(token)))).path("userId").asLong();
    }
}

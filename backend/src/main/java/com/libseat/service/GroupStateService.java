package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.entity.Group;
import com.libseat.entity.GroupMember;
import com.libseat.entity.Reservation;
import com.libseat.enums.AttendStatus;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.InviteStatus;
import com.libseat.enums.NoticeType;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.ViolationType;
import com.libseat.repository.GroupMemberRepository;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 队伍签到与缺席的事务边界（DESIGN §7.2）。
 *
 * <p>★ 为什么不写在 {@link com.libseat.scheduler.ReservationScheduler} 里：Spring 的声明式事务靠
 * AOP 代理，同类内部直接调用会绕过代理让 {@code @Transactional} 静默失效。这里每一步都是
 * 「改预约 + 删 slot + 记违规」的多写操作，失效就等于出现
 * 「整队取消了但座位还占着」或「座位释放了但违规没记上」的半案 —— 与 V1 修过的
 * {@code ReservationStateService} 是同一个教训。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupStateService {

    /** 时刻文案格式（与暂离通知同源，通知正文要能直接读）。 */
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservationRepository reservationRepository;

    private final ReservationSlotRepository reservationSlotRepository;

    private final GroupRepository groupRepository;

    private final GroupMemberRepository groupMemberRepository;

    private final CreditService creditService;

    private final RuleConfigService ruleConfigService;

    private final WaitlistStateService waitlistStateService;

    private final NoticeService noticeService;

    /**
     * 组长签到 = 全队签到。
     *
     * <p>★ 成员的 {@code attend_status} 刻意保持 {@code UNKNOWN}：组长能替全队把预约点开，
     * 但「谁真的坐下了」只能由本人现场确认 —— 混在一起写就等于用一次签到给全桌人做不在场证明。
     *
     * @param group 队伍
     * @param now   签到时刻
     * @return 被签到的预约条数
     */
    @Transactional
    public int checkInAll(Group group, LocalDateTime now) {
        List<Reservation> reservations = reservationRepository.findByGroupId(group.getId());
        int count = 0;
        for (Reservation reservation : reservations) {
            if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
                continue;
            }
            reservation.setStatus(ReservationStatus.CHECKED_IN);
            reservation.setCheckInTime(now);
            count++;
        }
        reservationRepository.saveAll(reservations);
        group.setStatus(GroupStatus.CHECKED_IN);
        groupRepository.save(group);
        log.info("组长签到完成全队签到：groupId={}, 预约 {} 条", group.getId(), count);
        return count;
    }

    /**
     * 成员宽限期后仍未入座：记 {@code ABSENT} + 违规，<b>不释放座位</b>。
     *
     * <p>★ 座位保留给队伍是 PRD §7.2 明写的口径：整桌是一个使用单元，人没来也把位置留在桌上，
     * 换人入座由组长与馆员现场处理。这里如果顺手删 slot，静音区会变成「四人桌只有三人能用」，
     * 而用户完全看不出为什么。
     *
     * @param member  缺席成员
     * @param group   队伍
     * @param now     判定时刻
     */
    @Transactional
    public void markMemberAbsent(GroupMember member, Group group, LocalDateTime now) {
        member.setAttendStatus(AttendStatus.ABSENT);
        groupMemberRepository.save(member);

        Reservation reservation = member.getSeatId() == null ? null
                : reservationRepository.findByGroupId(group.getId()).stream()
                .filter(r -> member.getSeatId().equals(r.getSeatId()))
                .findFirst().orElse(null);

        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "group.attend-grace-min");
        evidence.put("groupId", group.getId());
        evidence.put("reserveDate", BizConstants.formatDate(group.getReserveDate()));
        evidence.put("slotLabel", BizConstants.rangeLabel(group.getStartSlot(),
                BizConstants.hoursOfSlots(group.getEndSlot() - group.getStartSlot())));
        evidence.put("seatId", member.getSeatId());
        evidence.put("checkInTime", BizConstants.formatDateTime(
                reservation == null ? null : reservation.getCheckInTime()));
        evidence.put("deadline", BizConstants.formatDateTime(attendanceDeadline(reservation, now)));
        evidence.put("judgedAt", BizConstants.formatDateTime(now));
        creditService.applyViolation(member.getUserId(), ViolationType.GROUP_ABSENT, null,
                creditService.buildEvidence(evidence), reservation == null ? null : reservation.getId(), null);
        log.info("组队成员未入座：groupId={}, userId={}, seatId={}",
                group.getId(), member.getUserId(), member.getSeatId());
    }

    /**
     * 组长未在窗口内签到：整队取消并释放座位，只处罚组长。
     *
     * <p>★ 成员一条违规都不记：他们可能根本不知道组长没签到，而队伍没开起来不是他们的错。
     *
     * @param group 队伍
     * @param now   判定时刻
     * @return 被取消的预约条数
     */
    @Transactional
    public int cancelForLeaderAbsence(Group group, LocalDateTime now) {
        List<Reservation> reservations = reservationRepository.findByGroupId(group.getId());
        int count = 0;
        Long sampleReservationId = null;
        for (Reservation reservation : reservations) {
            if (reservation.getStatus() == ReservationStatus.CANCELLED) {
                continue;
            }
            reservation.setStatus(ReservationStatus.CANCELLED);
            reservation.setCancelTime(now);
            reservationSlotRepository.deleteByReservationId(reservation.getId());
            // 候补释放挂钩：与个人取消、暂离超时同一入口。整桌释放 N 把椅子就调用 N 次，
            // 同一排队键上的「一次释放只发一个 offer」判断会自然收敛，不会重复许诺同一个位置
            waitlistStateService.onReservationReleased(reservation, now);
            if (sampleReservationId == null) {
                sampleReservationId = reservation.getId();
            }
            count++;
        }
        reservationRepository.saveAll(reservations);
        group.setStatus(GroupStatus.CANCELLED);
        groupRepository.save(group);

        int checkInAfterMin = ruleConfigService.checkInAfterMin();
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("rule", "group.leader-check-in-after-min");
        evidence.put("groupId", group.getId());
        evidence.put("reserveDate", BizConstants.formatDate(group.getReserveDate()));
        evidence.put("slotLabel", BizConstants.rangeLabel(group.getStartSlot(),
                BizConstants.hoursOfSlots(group.getEndSlot() - group.getStartSlot())));
        evidence.put("deadline", BizConstants.formatDateTime(BizConstants.slotStart(
                group.getReserveDate(), group.getStartSlot()).plusMinutes(checkInAfterMin)));
        evidence.put("cancelledReservations", count);
        evidence.put("judgedAt", BizConstants.formatDateTime(now));
        creditService.applyViolation(group.getLeaderId(), ViolationType.LEADER_ABSENT, null,
                creditService.buildEvidence(evidence), sampleReservationId, null);
        // 组长缺席要通知的是**成员**：他们既没被扣分，也不知道队伍已经没了，
        // 不在场的成员如果继续赶来只会对着一张已被别人坐下的桌子站着（DESIGN §7.2）。
        String range = BizConstants.formatDate(group.getReserveDate()) + " "
                + BizConstants.rangeLabel(group.getStartSlot(),
                BizConstants.hoursOfSlots(group.getEndSlot() - group.getStartSlot()));
        noticeService.push(group.getLeaderId(), NoticeType.GROUP_LEADER_ABSENT,
                "队伍已取消：你未在签到窗口内签到",
                "第 " + group.getId() + " 队（" + range + "）因你未在 " + HM.format(
                        BizConstants.slotStart(group.getReserveDate(), group.getStartSlot())
                                .plusMinutes(checkInAfterMin))
                        + " 前签到而被取消，已释放 " + count + " 个座位。本次只处罚组长，成员不记违规。",
                NoticeService.REF_GROUP, group.getId(), null);
        for (GroupMember member : groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId())) {
            if (member.getUserId().equals(group.getLeaderId())
                    || member.getInviteStatus() != InviteStatus.CONFIRMED) {
                // 只告知「真的在这队里」的人：婉拒过或没确认的成员本来就不知道自己被取消了什么
                continue;
            }
            noticeService.push(member.getUserId(), NoticeType.GROUP_LEADER_ABSENT,
                    "队伍已取消：组长未签到",
                    "第 " + group.getId() + " 队（" + range + "）因组长未在签到窗口内签到而被取消，"
                            + "座位已释放。你不记任何违规，可以重新建队或直接预约散座。",
                    NoticeService.REF_GROUP, group.getId(), null);
        }
        log.info("组长未签到，整队取消：groupId={}, 释放 {} 条预约", group.getId(), count);
        return count;
    }

    /**
     * 某支队伍此刻「仍未入座且已过宽限期」的成员。
     *
     * <p>宽限期以队长那条预约的签到时刻为起点（同一次签到只有一个参照点，
     * 用 each 成员自己的时刻会因为签到同秒而看不出差别，却多一遍查询）。
     *
     * @param group 队伍
     * @param now   参照时刻
     * @return 待记缺席的成员
     */
    @Transactional(readOnly = true)
    public List<GroupMember> overdueAbsentMembers(Group group, LocalDateTime now) {
        if (group.getStatus() != GroupStatus.CHECKED_IN) {
            return List.of();
        }
        LocalDateTime earliest = earliestCheckInTime(group);
        if (earliest == null) {
            return List.of();
        }
        LocalDateTime deadline = earliest.plusMinutes(BizConstants.GROUP_ATTEND_GRACE_MIN);
        if (!deadline.isBefore(now)) {
            return List.of();
        }
        return groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId()).stream()
                .filter(member -> !"LEADER".equals(member.getMemberRole()))
                .filter(member -> member.getAttendStatus() == AttendStatus.UNKNOWN)
                .toList();
    }

    /**
     * 组长是否已经错过签到窗口（队伍还停在 RESERVED 且已过「开始 + 宽限」）。
     *
     * @param group 队伍
     * @param now   参照时刻
     * @return 是否该按组长缺席处理
     */
    @Transactional(readOnly = true)
    public boolean leaderMissedCheckIn(Group group, LocalDateTime now) {
        if (group.getStatus() != GroupStatus.RESERVED) {
            return false;
        }
        return BizConstants.slotStart(group.getReserveDate(), group.getStartSlot())
                .plusMinutes(ruleConfigService.checkInAfterMin())
                .isBefore(now);
    }

    private LocalDateTime earliestCheckInTime(Group group) {
        return reservationRepository.findByGroupId(group.getId()).stream()
                .map(Reservation::getCheckInTime)
                .filter(time -> time != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDateTime attendanceDeadline(Reservation reservation, LocalDateTime now) {
        if (reservation == null || reservation.getCheckInTime() == null) {
            return now;
        }
        return reservation.getCheckInTime().plusMinutes(BizConstants.GROUP_ATTEND_GRACE_MIN);
    }
}

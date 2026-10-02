package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.GroupDTO;
import com.libseat.dto.ReservationDTO;
import com.libseat.entity.Group;
import com.libseat.entity.GroupMember;
import com.libseat.entity.Seat;
import com.libseat.entity.SeatTable;
import com.libseat.entity.User;
import com.libseat.entity.Zone;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.GroupStatus;
import com.libseat.enums.InviteStatus;
import com.libseat.enums.SeatStatus;
import com.libseat.enums.UserRole;
import com.libseat.repository.GroupMemberRepository;
import com.libseat.repository.GroupRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.SeatTableRepository;
import com.libseat.repository.UserRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 组队预约（DESIGN §7.2）：建队 → 邀请 → 确认 → 到点锁定并按桌下单。
 *
 * <p>★ <b>占用一律落在 reservation + reservation_slot 上</b>，队伍表不持有任何占用真相：
 * 平面图、统计、暂离、候补释放这些「按占用说话」的地方因此完全不需要为组队加分支。
 * 这也决定了锁定必须是「整单一个事务」：桌上任一座位被抢，{@code createReservation} 抛 4091
 * 触发回滚，前面已插入的预约与 slot 行一起消失 —— 队伍要么整桌成功，要么一动不动。
 *
 * <p>★ 邀请有效期（10 分钟）用「成员自己的 createTime」而不是队伍邀请码的有效期：
 * 码还可以剩 8 分钟，但这位同学可能是 12 分钟前被邀请的。
 * 写路径（邀请 / 加入 / 确认 / 锁定）会把过期的 {@code INVITED} 落成 {@code EXPIRED}；
 * 读路径只按同一规则<b>临时换算</b>显示状态，不在只读事务里写库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

    /** 邀请码字符集：去掉容易看混的 I / O / 0 / 1。 */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    private final GroupRepository groupRepository;

    private final GroupMemberRepository groupMemberRepository;

    private final SeatRepository seatRepository;

    private final SeatTableRepository seatTableRepository;

    private final ZoneRepository zoneRepository;

    private final UserRepository userRepository;

    private final ReservationService reservationService;

    private final RuleConfigService ruleConfigService;

    private final OpenRuleService openRuleService;

    // ==================== 建队 ====================

    /**
     * 组长建队。
     *
     * @param leaderId 组长
     * @param role     组长角色（分区资格预检）
     * @param request  建队请求
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO create(Long leaderId, UserRole role, GroupDTO.GroupCreateRequest request) {
        Zone zone = zoneRepository.findById(request.getZoneId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分区不存在"));
        if (zone.getStatus() != FloorStatus.OPEN) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该分区已停用，不能组队");
        }
        if (!Boolean.TRUE.equals(zone.getAllowGroup()) && !Boolean.TRUE.equals(zone.getGroupRequired())) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    zone.getName() + " 不支持组队预约，请选择允许组队的分区");
        }
        if (!ZoneService.containsRole(zone.getEligibleRoles(), role)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE,
                    zone.getName() + " 仅对 " + zone.getEligibleRoles().replace(",", "、") + " 开放");
        }
        SeatTable table = seatTableRepository.findById(request.getTableId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "桌位不存在"));
        if (!table.getZoneId().equals(zone.getId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "桌位与分区不属于同一区域");
        }
        if (table.getStatus() != SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该桌位当前不可用");
        }
        List<Seat> seats = freeSeats(table.getId());
        if (seats.size() < BizConstants.GROUP_MIN_MEMBERS) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该桌可用座位不足 " + BizConstants.GROUP_MIN_MEMBERS + " 个，无法组队");
        }
        int maxMembers = request.getMaxMembers() == null ? seats.size() : request.getMaxMembers();
        int minMembers = request.getMinMembers() == null ? BizConstants.GROUP_MIN_MEMBERS : request.getMinMembers();
        if (minMembers > maxMembers) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "成团人数不能大于人数上限");
        }
        if (maxMembers > seats.size()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该桌只有 " + seats.size() + " 个可用座位，人数上限不能超过它");
        }
        validateTimeWindow(zone, request.getDate(), request.getStartSlot(), request.getDuration());

        LocalDateTime lockTime = BizConstants.slotStart(request.getDate(), request.getStartSlot())
                .minusMinutes(BizConstants.GROUP_LOCK_BEFORE_MIN);
        if (!lockTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "距开始不足 " + BizConstants.GROUP_LOCK_BEFORE_MIN + " 分钟，成员来不及确认；请选更晚的时段");
        }

        Group group = groupRepository.saveAndFlush(Group.builder()
                .leaderId(leaderId)
                .floorId(zone.getFloorId())
                .zoneId(zone.getId())
                .tableId(table.getId())
                .reserveDate(request.getDate())
                .startSlot(request.getStartSlot())
                .endSlot(request.getStartSlot() + BizConstants.slotsOf(request.getDuration()))
                .inviteCode(generateInviteCode())
                .inviteExpireTime(LocalDateTime.now().plusMinutes(BizConstants.GROUP_INVITE_TTL_MIN))
                .minMembers(minMembers)
                .maxMembers(maxMembers)
                .lockTime(lockTime)
                .status(GroupStatus.FORMING)
                .build());
        groupMemberRepository.saveAndFlush(GroupMember.builder()
                .groupId(group.getId())
                .userId(leaderId)
                .memberRole("LEADER")
                .inviteStatus(InviteStatus.CONFIRMED)
                .confirmTime(LocalDateTime.now())
                .build());
        log.info("组队成功：groupId={}, 桌位={}, 组长={}, 邀请码有效期到 {}, 锁定时刻={}",
                group.getId(), table.getTableNo(), leaderId, group.getInviteExpireTime(), lockTime);
        return view(requireGroup(group.getId()), leaderId);
    }

    // ==================== 邀请 / 加入 / 确认 / 退出 ====================

    /**
     * 组长按账号邀请成员。
     *
     * @param groupId  队伍
     * @param leaderId 操作人（必须组长）
     * @param username 被邀请人账号
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO invite(Long groupId, Long leaderId, String username) {
        Group group = requireGroup(groupId);
        requireLeader(group, leaderId);
        requireMutable(group);
        User invitee = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "账号 " + username + " 不存在"));
        if (invitee.getId().equals(group.getLeaderId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "组长已经在队里");
        }
        expireOverdueMembers(group);
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(groupId);
        if (countConfirmed(members) + countInvited(members) >= group.getMaxMembers()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "队伍已满（上限 " + group.getMaxMembers() + " 人），无法继续邀请");
        }
        Optional<GroupMember> existing = findMember(members, invitee.getId());
        if (existing.isPresent() && existing.get().getInviteStatus().counted()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "该同学已在队伍中并已确认");
        }
        if (existing.isPresent()) {
            GroupMember member = existing.get();
            member.setInviteStatus(InviteStatus.INVITED);
            member.setConfirmTime(null);
            groupMemberRepository.save(member);
        } else {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .groupId(groupId)
                    .userId(invitee.getId())
                    .memberRole("MEMBER")
                    .inviteStatus(InviteStatus.INVITED)
                    .build());
        }
        return view(requireGroup(groupId), leaderId);
    }

    /**
     * 用邀请码入队（只登记待确认，不直接算人数）。
     *
     * @param inviteCode 邀请码
     * @param userId     加入者
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO join(String inviteCode, Long userId) {
        Group group = groupRepository.findByInviteCode(inviteCode.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_INVITE_INVALID,
                        "邀请码无效，请向组长重新索取"));
        if (group.getInviteExpireTime() == null || group.getInviteExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.GROUP_INVITE_INVALID,
                    "邀请码已过期（有效期至 " + BizConstants.formatDateTime(group.getInviteExpireTime()) + "）");
        }
        requireMutable(group);
        if (userId.equals(group.getLeaderId())) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "组长已在队里");
        }
        expireOverdueMembers(group);
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId());
        Optional<GroupMember> existing = findMember(members, userId);
        if (existing.isPresent() && existing.get().getInviteStatus().counted()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "你已经在这支队伍里了");
        }
        if (countConfirmed(members) + countInvited(members) >= group.getMaxMembers()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "队伍已满（上限 " + group.getMaxMembers() + " 人）");
        }
        if (existing.isPresent()) {
            GroupMember member = existing.get();
            member.setInviteStatus(InviteStatus.INVITED);
            groupMemberRepository.save(member);
        } else {
            groupMemberRepository.saveAndFlush(GroupMember.builder()
                    .groupId(group.getId())
                    .userId(userId)
                    .memberRole("MEMBER")
                    .inviteStatus(InviteStatus.INVITED)
                    .build());
        }
        return view(requireGroup(group.getId()), userId);
    }

    /**
     * 成员确认。
     *
     * @param groupId 队伍
     * @param userId  确认人
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO confirm(Long groupId, Long userId) {
        Group group = requireGroup(groupId);
        expireOverdueMembers(group);
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "你不在该队伍中"));
        if (member.getInviteStatus() == InviteStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "已经确认过了");
        }
        if (member.getInviteStatus() == InviteStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.GROUP_INVITE_INVALID,
                    "邀请已超过 " + BizConstants.GROUP_INVITE_TTL_MIN + " 分钟有效期，请让组长重新邀请");
        }
        if (member.getInviteStatus() == InviteStatus.DECLINED) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "已婉拒，需要组长重新邀请");
        }
        requireMutable(group);
        member.setInviteStatus(InviteStatus.CONFIRMED);
        member.setConfirmTime(LocalDateTime.now());
        groupMemberRepository.save(member);
        return view(requireGroup(groupId), userId);
    }

    /**
     * 成员退出（仅 FORMING 且未到锁定时刻）。
     *
     * @param groupId 队伍
     * @param userId  退出人
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO quit(Long groupId, Long userId) {
        Group group = requireGroup(groupId);
        if (userId.equals(group.getLeaderId())) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED, "组长退出请直接取消队伍，队伍不能没有组长");
        }
        requireMutable(group);
        GroupMember member = groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "你不在该队伍中"));
        member.setInviteStatus(InviteStatus.DECLINED);
        groupMemberRepository.save(member);
        return view(requireGroup(groupId), userId);
    }

    /**
     * 组长取消队伍（仅未锁定；已下单的队伍交给预约侧取消）。
     *
     * @param groupId 队伍
     * @param userId  操作人（必须组长）
     * @return 队伍视图
     */
    @Transactional
    public GroupDTO.GroupVO cancel(Long groupId, Long userId) {
        Group group = requireGroup(groupId);
        requireLeader(group, userId);
        if (group.getStatus().terminal()) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "该队伍已是「" + group.getStatus().getText() + "」，无需取消");
        }
        if (group.getStatus() != GroupStatus.FORMING) {
            // 已下单的队占着真实座位，必须由预约侧的取消逻辑释放（含候补挂钩），
            // 队伍这里直接改状态会留下「队伍取消了但座位还占着」的脏占用
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "队伍已" + group.getStatus().getText() + "，请在「我的预约」逐条取消或联系馆员");
        }
        group.setStatus(GroupStatus.CANCELLED);
        groupRepository.save(group);
        log.info("队伍已取消：groupId={}, 组长={}", groupId, userId);
        return view(requireGroup(groupId), userId);
    }

    // ==================== 锁定与整桌下单 ====================

    /**
     * 到点锁定并按桌下单。
     *
     * <p>★ 整单只有一个事务：任何一步抛异常（含座位冲突 4091）都会把已插入的预约与 slot 行一起回滚，
     * 队伍状态也因此停在 {@code FORMING}，下一轮扫描会重试。
     *
     * @param groupId 队伍 ID
     * @return 本次是否完成了下单
     */
    @Transactional
    public boolean lockAndOrder(Long groupId) {
        Group group = requireGroup(groupId);
        if (group.getStatus() != GroupStatus.FORMING) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (group.getLockTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.STATUS_NOT_ALLOWED,
                    "未到锁定时刻（" + BizConstants.formatDateTime(group.getLockTime()) + "）");
        }
        expireOverdueMembers(group);
        List<GroupMember> confirmed = confirmedMembers(group.getId());
        if (confirmed.size() < group.getMinMembers()) {
            // 人数不足：整队 FAILED。此时一个座位都没占，不需要释放动作。
            group.setStatus(GroupStatus.FAILED);
            groupRepository.save(group);
            log.info("队伍未成团：groupId={}, 确认 {} 人 < 最少 {} 人",
                    groupId, confirmed.size(), group.getMinMembers());
            return false;
        }
        List<Seat> seats = freeSeats(group.getTableId());
        if (seats.size() < confirmed.size()) {
            group.setStatus(GroupStatus.FAILED);
            groupRepository.save(group);
            log.warn("桌位可用座位不足，未成团：groupId={}, 需要 {} 人，可用 {} 座",
                    groupId, confirmed.size(), seats.size());
            return false;
        }

        group.setStatus(GroupStatus.LOCKED);
        groupRepository.save(group);

        int duration = BizConstants.hoursOfSlots(group.getEndSlot() - group.getStartSlot());
        List<GroupMember> assigned = new ArrayList<>();
        for (int i = 0; i < confirmed.size(); i++) {
            GroupMember member = confirmed.get(i);
            Seat seat = seats.get(i);
            ReservationDTO.ReservationCreateRequest request = new ReservationDTO.ReservationCreateRequest();
            request.setSeatId(seat.getId());
            request.setDate(group.getReserveDate());
            request.setStartSlot(group.getStartSlot());
            request.setDuration(duration);
            // ★ 复用个人预约的七步校验：成员各自的信用闸门、单日累计、时段重叠都在这一步生效
            reservationService.createReservation(member.getUserId(), request, groupId);
            member.setSeatId(seat.getId());
            assigned.add(member);
        }
        groupMemberRepository.saveAll(assigned);

        group.setStatus(GroupStatus.RESERVED);
        groupRepository.save(group);
        log.info("整桌下单成功：groupId={}, {} 人，首个座位 {}",
                groupId, assigned.size(), seats.get(0).getSeatNo());
        return true;
    }

    // ==================== 查询 ====================

    /**
     * 队伍详情。
     *
     * @param groupId 队伍 ID
     * @param userId  查看人（决定邀请码是否回显与可动作）
     * @return 队伍视图
     */
    @Transactional(readOnly = true)
    public GroupDTO.GroupVO detail(Long groupId, Long userId) {
        return view(requireGroup(groupId), userId);
    }

    /**
     * 我的队伍（作为组长或成员的非终态队伍）。
     *
     * @param userId 当前用户
     * @return 队伍列表（新的在前）
     */
    @Transactional(readOnly = true)
    public List<GroupDTO.GroupVO> mine(Long userId) {
        List<GroupDTO.GroupVO> result = new ArrayList<>();
        for (GroupMember member : groupMemberRepository.findByUserIdOrderByIdDesc(userId)) {
            Group group = groupRepository.findById(member.getGroupId()).orElse(null);
            if (group == null || group.getStatus().terminal()) {
                continue;
            }
            result.add(view(group, userId));
        }
        return result;
    }

    // ==================== 内部工具 ====================

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "队伍不存在"));
    }

    /**
     * 组长专属动作的判定。
     *
     * <p>★ 用 4031 而不是 4030：4030 表示「角色不在这个路径的权限矩阵里」，而这里是登录与角色都对、
     * 越的是「这条记录不归你管」，与分区资格越权同一码。
     */
    private void requireLeader(Group group, Long userId) {
        if (!group.getLeaderId().equals(userId)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ELIGIBLE, "该操作只有组长可以做");
        }
    }

    private void requireMutable(Group group) {
        if (!group.getStatus().memberMutable()) {
            throw new BusinessException(ErrorCode.GROUP_LOCKED,
                    "队伍已" + group.getStatus().getText() + "，不能再增减成员");
        }
        if (group.getLockTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.GROUP_LOCKED,
                    "已过锁定时刻（" + BizConstants.formatDateTime(group.getLockTime()) + "），不能再增减成员");
        }
    }

    private List<Seat> freeSeats(Long tableId) {
        return seatRepository.findByTableId(tableId).stream()
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .sorted(Comparator.comparing(Seat::getSeatNo))
                .toList();
    }

    /** 已过有效期但仍是 INVITED 的成员，落成 EXPIRED（写路径专用）。 */
    private void expireOverdueMembers(Group group) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(BizConstants.GROUP_INVITE_TTL_MIN);
        for (GroupMember member : groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId())) {
            if (overdue(member, deadline)) {
                member.setInviteStatus(InviteStatus.EXPIRED);
                groupMemberRepository.save(member);
            }
        }
    }

    private boolean overdue(GroupMember member, LocalDateTime deadline) {
        return member.getInviteStatus() == InviteStatus.INVITED
                && member.getCreateTime() != null
                && member.getCreateTime().isBefore(deadline);
    }

    /** 显示用的状态：只读事务里不落库，但按同一条有效期规则呈现。 */
    private InviteStatus shownStatus(GroupMember member, LocalDateTime deadline) {
        return overdue(member, deadline) ? InviteStatus.EXPIRED : member.getInviteStatus();
    }

    private List<GroupMember> confirmedMembers(Long groupId) {
        return groupMemberRepository.findByGroupIdOrderByIdAsc(groupId).stream()
                .filter(member -> member.getInviteStatus().counted())
                // 组长排最前：让组长优先拿到桌头座位，签到与缺席判定都在组长身上
                .sorted(Comparator.comparing((GroupMember m) -> "LEADER".equals(m.getMemberRole()) ? 0 : 1)
                        .thenComparing(GroupMember::getId))
                .toList();
    }

    private long countConfirmed(List<GroupMember> members) {
        return members.stream().filter(m -> m.getInviteStatus().counted()).count();
    }

    private long countInvited(List<GroupMember> members) {
        return members.stream().filter(m -> m.getInviteStatus() == InviteStatus.INVITED).count();
    }

    private Optional<GroupMember> findMember(List<GroupMember> members, Long userId) {
        return members.stream().filter(m -> m.getUserId().equals(userId)).findFirst();
    }

    /**
     * 建队时的时间与时长校验。
     *
     * <p>★ 与 {@code ReservationService} 的第 2/3/4/4b 步同口径，但作用只是「提前给即时反馈」；
     * 权威判定仍在锁定时的下单链上 —— 那里整单回滚能保证座位一动不动，所以不必把七步复制一遍。
     */
    private void validateTimeWindow(Zone zone, LocalDate date, int startSlot, int duration) {
        LocalDate today = LocalDate.now();
        int advanceDays = ruleConfigService.advanceDays();
        if (date.isBefore(today) || date.isAfter(today.plusDays(advanceDays - 1L))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "只能预约今天起 " + advanceDays + " 天内的座位");
        }
        OpenRuleService.DayRule day = openRuleService.resolve(date);
        if (day.isClosed()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, date + " 闭馆，不能组队");
        }
        if (!BizConstants.validSlotIndex(startSlot)
                || startSlot + BizConstants.slotsOf(duration) > day.getOpenEnd()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "预约时段超出开放时间（"
                    + BizConstants.slotText(BizConstants.SLOT_INDEX_MIN) + "-"
                    + BizConstants.slotText(day.getOpenEnd()) + "）");
        }
        if (!BizConstants.slotStart(date, startSlot).isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不能预约已过去的时段");
        }
        if (!ruleConfigService.allowedDurations().contains(duration)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "时长只能选择 " + ruleConfigService.allowedDurations() + " 小时");
        }
        if (zone.getMaxDurationMin() != null && duration * 60 > zone.getMaxDurationMin()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    zone.getName() + " 单次最长 " + zone.getMaxDurationMin() + " 分钟");
        }
    }

    private String generateInviteCode() {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(BizConstants.GROUP_INVITE_CODE_LENGTH);
            for (int i = 0; i < BizConstants.GROUP_INVITE_CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            String code = sb.toString();
            if (groupRepository.findByInviteCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邀请码生成失败，请稍后再试");
    }

    private GroupDTO.GroupVO view(Group group, Long viewerId) {
        boolean leader = group.getLeaderId().equals(viewerId);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(BizConstants.GROUP_INVITE_TTL_MIN);
        List<GroupMember> members = groupMemberRepository.findByGroupIdOrderByIdAsc(group.getId());
        int confirmed = 0;
        int invited = 0;
        List<GroupDTO.MemberVO> memberVOS = new ArrayList<>();
        for (GroupMember member : members) {
            InviteStatus status = shownStatus(member, deadline);
            if (status.counted()) {
                confirmed++;
            }
            if (status == InviteStatus.INVITED) {
                invited++;
            }
            User user = userRepository.findById(member.getUserId()).orElse(null);
            Seat seat = member.getSeatId() == null ? null
                    : seatRepository.findById(member.getSeatId()).orElse(null);
            memberVOS.add(GroupDTO.MemberVO.builder()
                    .memberId(member.getId())
                    .userId(member.getUserId())
                    .username(user == null ? null : user.getUsername())
                    .realName(user == null ? "（账号已注销）" : user.getRealName())
                    .memberRole(member.getMemberRole())
                    .memberRoleText("LEADER".equals(member.getMemberRole()) ? "组长" : "成员")
                    .inviteStatus(status.name())
                    .inviteStatusText(status.getText())
                    .confirmTime(BizConstants.formatDateTime(member.getConfirmTime()))
                    .seatId(member.getSeatId())
                    .seatNo(seat == null ? null : seat.getSeatNo())
                    .attendStatus(member.getAttendStatus().name())
                    .attendStatusText(member.getAttendStatus().getText())
                    .canConfirm(status == InviteStatus.INVITED && actionsOpen(group))
                    .confirmDisabledReason(confirmDisabledReason(status, group))
                    .canQuit(status.counted() && !"LEADER".equals(member.getMemberRole()) && actionsOpen(group))
                    .quitDisabledReason(quitDisabledReason(member, status, group))
                    .build());
        }
        int duration = BizConstants.hoursOfSlots(group.getEndSlot() - group.getStartSlot());
        String cancelReason = cancelDisabledReason(group);
        Zone zone = zoneRepository.findById(group.getZoneId()).orElse(null);
        SeatTable table = seatTableRepository.findById(group.getTableId()).orElse(null);
        return GroupDTO.GroupVO.builder()
                .groupId(group.getId())
                .leaderId(group.getLeaderId())
                .leaderName(userRepository.findById(group.getLeaderId()).map(User::getRealName).orElse(null))
                .floorId(group.getFloorId())
                .zoneId(group.getZoneId())
                .zoneName(zone == null ? null : zone.getName())
                .tableId(group.getTableId())
                .tableNo(table == null ? null : table.getTableNo())
                .date(BizConstants.formatDate(group.getReserveDate()))
                .startSlot(group.getStartSlot())
                .endSlot(group.getEndSlot())
                .timeRange(BizConstants.rangeLabel(group.getStartSlot(), duration))
                .durationHours(duration)
                .status(group.getStatus().name())
                .statusText(group.getStatus().getText())
                .minMembers(group.getMinMembers())
                .maxMembers(group.getMaxMembers())
                .confirmedCount(confirmed)
                .invitedCount(invited)
                .lockTime(BizConstants.formatDateTime(group.getLockTime()))
                .minutesToLock((int) Duration.between(LocalDateTime.now(), group.getLockTime()).toMinutes())
                .inviteCode(leader ? group.getInviteCode() : null)
                .inviteExpireTime(leader ? BizConstants.formatDateTime(group.getInviteExpireTime()) : null)
                .inviteAlive(group.getInviteExpireTime() != null
                        && group.getInviteExpireTime().isAfter(LocalDateTime.now()))
                .leader(leader)
                .canCancel(cancelReason == null)
                .cancelDisabledReason(cancelReason)
                .myInviteStatus(findMember(members, viewerId)
                        .map(m -> shownStatus(m, deadline).getText()).orElse(null))
                .members(memberVOS)
                .build();
    }

    private boolean actionsOpen(Group group) {
        return group.getStatus().memberMutable() && group.getLockTime().isAfter(LocalDateTime.now());
    }

    private String confirmDisabledReason(InviteStatus status, Group group) {
        if (status == InviteStatus.CONFIRMED) {
            return "已确认";
        }
        if (status == InviteStatus.EXPIRED) {
            return "邀请已超过 " + BizConstants.GROUP_INVITE_TTL_MIN + " 分钟有效期";
        }
        if (status == InviteStatus.DECLINED) {
            return "已婉拒，需要组长重新邀请";
        }
        if (!group.getStatus().memberMutable()) {
            return "队伍已" + group.getStatus().getText();
        }
        if (group.getLockTime().isBefore(LocalDateTime.now())) {
            return "已过锁定时刻";
        }
        return null;
    }

    private String quitDisabledReason(GroupMember member, InviteStatus status, Group group) {
        if ("LEADER".equals(member.getMemberRole())) {
            return "组长不能退出，请直接取消队伍";
        }
        if (status == InviteStatus.DECLINED) {
            return "已经退出过了";
        }
        if (!group.getStatus().memberMutable()) {
            return "队伍已" + group.getStatus().getText() + "，成员已冻结";
        }
        if (group.getLockTime().isBefore(LocalDateTime.now())) {
            return "已过锁定时刻，成员已冻结";
        }
        return null;
    }

    private String cancelDisabledReason(Group group) {
        if (group.getStatus().terminal()) {
            return "队伍已是「" + group.getStatus().getText() + "」";
        }
        if (group.getStatus() != GroupStatus.FORMING) {
            return "队伍已" + group.getStatus().getText() + "，取消请在「我的预约」逐条处理";
        }
        return null;
    }
}

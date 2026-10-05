package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.dto.StaffDashboardDTO;
import com.libseat.dto.StatsDTO;
import com.libseat.entity.PatrolTask;
import com.libseat.enums.AppealStatus;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.PatrolLevel;
import com.libseat.enums.PenaltyStatus;
import com.libseat.repository.AppealRepository;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.PatrolTaskRepository;
import com.libseat.repository.PenaltyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 馆员监控大屏的聚合读（TASKS-V2 T-V2-22）。
 *
 * <p>★ 只聚合，不派生：占用与统计数字全部转调 {@code AdminStatisticsService}
 * （它内部走 {@code OccupancyService.derive/usageOf} 这个唯一派生入口）。
 * 大屏要是自己再算一遍「谁在座」，V2 从 S1 守到现在的「占用只从 t_reservation_slot 派生」
 * 就有了第二个真相源 —— 两处不一致时，馆员信哪个？
 *
 * <p>★ 异常计数走各自的表，不走各自的看板接口：
 * {@code PatrolService.board(...)} 会为每一行补「椅子上是谁」的预约/用户/座位三次批量查询，
 * 而大屏只要三个数字。用「同一条未处理判定」（{@code open_flag}）取数，
 * 既满足验收里的「单接口 &lt;1s」，也不会和点开看板看到的条数不一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffDashboardService {

    /** 时段热度窗口：大屏只关心今天。 */
    private static final int HEAT_DAYS = 1;

    private final AdminStatisticsService adminStatisticsService;

    private final OpenRuleService openRuleService;

    private final PeakService peakService;

    private final PatrolTaskRepository patrolTaskRepository;

    private final AppealRepository appealRepository;

    private final PenaltyRepository penaltyRepository;

    private final LeaveRecordRepository leaveRecordRepository;

    /**
     * 大屏整页数据。
     *
     * @param now 判定时刻（测试注入用；生产入口传 {@code LocalDateTime.now()}）
     * @return 大屏 VO
     */
    @Transactional(readOnly = true)
    public StaffDashboardDTO.DashboardVO dashboard(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        int slot = BizConstants.clampToSlotRange(BizConstants.slotOf(now.toLocalTime()));
        StatsDTO.OverviewVO overview = adminStatisticsService.overview();
        List<StatsDTO.FloorUsageVO> floors = adminStatisticsService.floorUsage(today, slot);

        return StaffDashboardDTO.DashboardVO.builder()
                .generatedAt(BizConstants.formatDateTime(now))
                .date(BizConstants.formatDate(today))
                .slotIndex(slot)
                .slotLabel(BizConstants.slotLabel(slot))
                .closedToday(openRuleService.isClosed(today))
                .overview(overview)
                .onLeave(leaveRecordRepository.countByStatusAndOpenFlag(
                        LeaveStatus.OPEN, LeaveStatus.OPEN_FLAG))
                .floors(floors)
                .heat(adminStatisticsService.slotHeat(HEAT_DAYS))
                .anomalies(anomalies(now))
                .build();
    }

    private StaffDashboardDTO.AnomalyVO anomalies(LocalDateTime now) {
        List<PatrolTask> open = patrolTaskRepository.findByOpenFlagOrderByIdAsc(PatrolTaskRepository.OPEN_FLAG);
        long high = open.stream().filter(task -> task.getLevel() == PatrolLevel.HIGH).count();
        long mid = open.stream().filter(task -> task.getLevel() == PatrolLevel.MID).count();
        return StaffDashboardDTO.AnomalyVO.builder()
                .patrolOpen((long) open.size())
                .patrolHigh(high)
                .patrolMid(mid)
                .patrolLow(open.size() - high - mid)
                // 取数按 ID 升序，第一条就是等最久的那条（唯一索引保证每座最多一条未处理）
                .patrolOldestMinutes(open.isEmpty() ? 0L
                        : Math.max(0, Duration.between(open.get(0).getCreateTime(), now).toMinutes()))
                .appealsPending(appealRepository.countByStatus(AppealStatus.PENDING))
                .appealsEscalated(appealRepository.countByStatus(AppealStatus.ESCALATED))
                .activePenaltyUsers((long) penaltyRepository.findByStatusOrderByIdAsc(PenaltyStatus.ACTIVE)
                        .stream().filter(penalty -> penalty.activeAt(now))
                        .map(penalty -> penalty.getUserId()).distinct().count())
                .peakZones((long) peakService.activeStates(now).size())
                .build();
    }
}

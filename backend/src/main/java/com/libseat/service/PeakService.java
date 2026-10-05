package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.Floor;
import com.libseat.entity.PeakWindow;
import com.libseat.entity.Seat;
import com.libseat.entity.Zone;
import com.libseat.entity.ZonePeakState;
import com.libseat.enums.FloorStatus;
import com.libseat.enums.UserRole;
import com.libseat.repository.FloorRepository;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.ZonePeakStateRepository;
import com.libseat.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 高峰识别与调控（DESIGN §8.1-8.2，T-V2-18）。
 *
 * <p>两条判定链合成一个入口：
 * <ul>
 *   <li><b>静态高峰</b>：{@code t_peak_window} 按日型 + 时段命中，且必须按 {@code scope}
 *       过滤 —— 分区级窗口只该管自己那个区，早先平面图只按格号取最严的一条，
 *       等于把「研讨区 18:00 限流」贴到了全馆每一层；</li>
 *   <li><b>动态高峰</b>：占用率连续超阈后写入 {@code t_zone_peak_state}，
 *       命中效果与静态高峰相同（时长压缩）。</li>
 * </ul>
 *
 * <p>★ 占用率一律走 {@link OccupancyService#usageOf}：余量卡片显示 85% 而系统按 92% 压时长，
 * 就是两处各算一遍必然会出现的结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeakService {

    private final OpenRuleService openRuleService;

    private final ZoneRepository zoneRepository;

    private final SeatRepository seatRepository;

    private final FloorRepository floorRepository;

    private final OccupancyService occupancyService;

    private final ZonePeakStateRepository peakStateRepository;

    private final RuleConfigService ruleConfigService;

    /**
     * 分区在指定日期 + 时段的占用率（百分数），与余量卡片同一份派生。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 时段
     * @return 占用率
     */
    private double usageRate(Zone zone, LocalDate date, int slot) {
        Floor floor = floorRepository.findById(zone.getFloorId()).orElse(null);
        boolean closed = (floor != null && floor.getStatus() == FloorStatus.CLOSED)
                || zone.getStatus() == FloorStatus.CLOSED;
        List<Seat> seats = seatRepository.findByZoneId(zone.getId());
        return occupancyService.usageOf(seats, closed, occupancyService.getOccupancy(date, slot), slot).rate();
    }

    /**
     * 命中该分区该格的静态高峰窗口（取最严的一条）。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 时段序号
     * @return 命中的窗口，未命中为 null
     */
    public PeakWindow staticPeak(Zone zone, LocalDate date, int slot) {
        OpenRuleService.DayRule day = openRuleService.resolve(date);
        PeakWindow strictest = null;
        for (PeakWindow peak : day.getPeaks()) {
            if (!Boolean.TRUE.equals(peak.getEnabled())
                    || peak.getStartSlot() == null || peak.getEndSlot() == null
                    || slot < peak.getStartSlot() || slot >= peak.getEndSlot()) {
                continue;
            }
            if (!scopeHits(peak, zone)) {
                continue;
            }
            if (strictest == null || stricter(peak, strictest)) {
                strictest = peak;
            }
        }
        return strictest;
    }

    /**
     * 动态高峰此刻是否生效。
     *
     * @param zoneId 分区
     * @param now    当前时刻
     * @return 是否处于动态高峰
     */
    @Transactional(readOnly = true)
    public boolean dynamicPeak(Long zoneId, LocalDateTime now) {
        return peakStateRepository.findById(zoneId)
                .map(state -> state.activeAt(now))
                .orElse(false);
    }

    /**
     * 该分区该时刻算不算高峰（静态 ∨ 动态）。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 时段序号
     * @param now  当前时刻
     * @return 是否高峰
     */
    public boolean isPeak(Zone zone, LocalDate date, int slot, LocalDateTime now) {
        return staticPeak(zone, date, slot) != null || dynamicPeak(zone.getId(), now);
    }

    /**
     * 有效单次时长上限（分钟）：{@code min(分区或教师覆盖, 静态窗口, peak.compress-min)}。
     *
     * <p>非高峰时原样返回分区上限（可能为 null，表示不设分区级上限）。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 起始时段
     * @param role 预约人角色
     * @param now  当前时刻
     * @return 上限分钟数，null 表示不设上限
     */
    public Integer effectiveMaxDurationMin(Zone zone, LocalDate date, int slot, UserRole role, LocalDateTime now) {
        Integer base = role == UserRole.TEACHER
                ? ruleConfigService.maxDurationTeacherMin()
                : zone.getMaxDurationMin();
        PeakWindow peak = staticPeak(zone, date, slot);
        boolean dynamic = dynamicPeak(zone.getId(), now);
        if (peak == null && !dynamic) {
            return base;
        }
        Integer limit = ruleConfigService.peakCompressMin();
        if (peak != null && peak.getMaxDurationMin() != null && peak.getMaxDurationMin() < limit) {
            limit = peak.getMaxDurationMin();
        }
        return base == null ? limit : Math.min(base, limit);
    }

    /**
     * 高峰时段的开放时刻：命中窗口且窗口设了 {@code advanceOpenSlot} 时，
     * 该格要到「前一天 peak.open-time」才开放。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 起始时段
     * @return 开放时刻；不需要提前闸门时为 null
     */
    public LocalDateTime advanceOpenAt(Zone zone, LocalDate date, int slot) {
        PeakWindow peak = staticPeak(zone, date, slot);
        if (peak == null || peak.getAdvanceOpenSlot() == null || slot < peak.getAdvanceOpenSlot()) {
            return null;
        }
        LocalTime openAt = ruleConfigService.peakOpenTime();
        return date.minusDays(1).atTime(openAt);
    }

    /**
     * 高峰闸门：未到开放时刻即拒绝（4101 带开放时刻文案）。
     *
     * @param zone 分区
     * @param date 日期
     * @param slot 起始时段
     * @param now  当前时刻
     */
    public void requireOpen(Zone zone, LocalDate date, int slot, LocalDateTime now) {
        LocalDateTime openAt = advanceOpenAt(zone, date, slot);
        if (openAt != null && now.isBefore(openAt)) {
            throw new BusinessException(ErrorCode.PEAK_BANNED,
                    "该时段属高峰，" + BizConstants.formatDateTime(openAt) + " 起才可预约");
        }
    }

    /**
     * 一轮采样：按当前时段的实时占用率推进各分区的高峰状态。
     *
     * <p>★ 回落不立即解除：已认定的高峰至少撑满 {@code dynamic-hold-min}，
     * 否则占用率在阈值线附近来回摆时，用户会看到同一张桌子一分钟前能约 4 小时、现在只能约 2 小时。
     *
     * @param date 采样日期（今天）
     * @param slot 采样时段（当前格）
     * @param now  采样时刻
     * @return 本轮处于高峰中的分区数
     */
    @Transactional
    public int detect(LocalDate date, int slot, LocalDateTime now) {
        int threshold = ruleConfigService.peakDynamicThreshold();
        int sustainMin = ruleConfigService.peakDynamicSustainMin();
        int holdMin = ruleConfigService.peakDynamicHoldMin();
        int active = 0;
        for (Zone zone : zoneRepository.findAll()) {
            if (zone.getStatus() != FloorStatus.OPEN) {
                continue;
            }
            ZonePeakState state = peakStateRepository.findById(zone.getId())
                    .orElseGet(() -> ZonePeakState.builder().zoneId(zone.getId()).build());
            double rate = usageRate(zone, date, slot);
            state.setRatio(rate);
            if (rate >= threshold) {
                if (state.getSinceTime() == null) {
                    state.setSinceTime(now);
                }
                if (Duration.between(state.getSinceTime(), now).toMinutes() >= sustainMin) {
                    LocalDateTime until = now.plusMinutes(holdMin);
                    if (state.getUntilTime() == null || state.getUntilTime().isBefore(until)) {
                        state.setUntilTime(until);
                    }
                }
            } else {
                // 连续被打断：重新计时，但已生效的高峰保持到 until 自然到期
                state.setSinceTime(null);
            }
            peakStateRepository.save(state);
            if (state.activeAt(now)) {
                active++;
            }
        }
        return active;
    }

    private boolean scopeHits(PeakWindow peak, Zone zone) {
        return switch (peak.getScope() == null ? "GLOBAL" : peak.getScope()) {
            case "ZONE" -> zone.getId().equals(peak.getZoneId());
            case "FLOOR" -> zone.getFloorId().equals(peak.getFloorId());
            default -> true;
        };
    }

    /** 上限更小者更严；没写上限的窗口只作为高峰标记，不参与压缩。 */
    private boolean stricter(PeakWindow candidate, PeakWindow current) {
        Integer a = candidate.getMaxDurationMin();
        Integer b = current.getMaxDurationMin();
        if (a == null) {
            return false;
        }
        return b == null || a < b;
    }

    /**
     * 供管理端查看当前动态高峰状态。
     *
     * @param now 当前时刻
     * @return 生效中的状态列表
     */
    @Transactional(readOnly = true)
    public List<ZonePeakState> activeStates(LocalDateTime now) {
        return peakStateRepository.findByUntilTimeAfter(now);
    }
}

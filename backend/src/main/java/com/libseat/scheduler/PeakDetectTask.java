package com.libseat.scheduler;

import com.libseat.common.BizConstants;
import com.libseat.service.OpenRuleService;
import com.libseat.service.PeakService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 动态高峰采样（DESIGN §10：每 5 分钟一轮）。
 *
 * <p>本类只做「要不要跑这一轮」的时间闸门，判定与写库全在
 * {@link PeakService#detect}，与其余调度器保持同一分层：调度器不写业务规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeakDetectTask {

    private final PeakService peakService;

    private final OpenRuleService openRuleService;

    /**
     * 每 5 分钟按当前时段采一次占用率。
     *
     * <p>闭馆日与开放窗口之外（早于 08:00、晚于 22:00）直接跳过：那时馆内没有占用，
     * 采出来的 0% 会把「连续超阈」的计时打断，等于每天夜里自动把所有高峰状态清零，
     * 白天的认定就永远攒不满持续时长。
     */
    @Scheduled(fixedRate = 300_000L, initialDelay = 45_000L)
    public void detectPeak() {
        LocalDate today = LocalDate.now();
        if (openRuleService.isClosed(today)) {
            return;
        }
        int slot = BizConstants.slotOf(LocalTime.now());
        if (slot < BizConstants.SLOT_INDEX_MIN || slot > BizConstants.SLOT_INDEX_MAX) {
            return;
        }
        int active = peakService.detect(today, slot, LocalDateTime.now());
        log.info("动态高峰采样：当前格 {}，{} 个分区处于高峰", BizConstants.slotText(slot), active);
    }
}

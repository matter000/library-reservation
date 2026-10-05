package com.libseat.scheduler;

import com.libseat.service.WaitlistStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 候补 offer 过期扫描（DESIGN §8.3）。
 *
 * <p>与其余调度器同样只做时间闸门，判定与写库在 {@link WaitlistStateService}。
 * 闭馆时刻不需要特判：offer 只在开放时段内发出，且过期是「时间到了」而不是「馆内有没有人」。
 *
 * <p>★ 过期<b>不记违规</b>：offer 只是「轮到你尝试」，座位从头到尾没被这个人占过，
 * 没有可罚的占用事实（TASKS-V2 T-V2-19 明确要求）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitlistExpireTask {

    private final WaitlistStateService waitlistStateService;

    /**
     * 每分钟回收已过窗口的 offer，并把位置顺延给下一位。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 50_000L)
    public void expireOffers() {
        int count = waitlistStateService.expireOverdue(LocalDateTime.now());
        if (count > 0) {
            log.info("定时任务：{} 条候补 offer 已过期并顺延下一位", count);
        }
    }
}

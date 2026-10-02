package com.libseat.scheduler;

import com.libseat.entity.User;
import com.libseat.repository.UserRepository;
import com.libseat.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 周恢复任务（DESIGN §6.4）：每周一 03:00 给「上一自然周零违规」的用户 +5 分。
 *
 * <p>★ 为什么单独一个类而不是塞进 {@link ReservationScheduler}：那边的节奏是分钟级、
 * 语义是「座位占用流转」；这里是周级、语义是「信用恢复」，两者的失败影响面完全不同，
 * 混在一个类里会让「周任务出错」看起来像「扫描出了问题」。
 *
 * <p>★ 周期按「本周一 00:00」切割而不是「now - 7 天」：后者会让窗口每天漂移，
 * 用户在周三被评估时统计的其实是「上周三到本周三」，与 PRD 说的「上一自然周」不是一回事。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyCreditTask {

    private final UserRepository userRepository;

    private final CreditService creditService;

    /**
     * 每周一 03:00 执行。
     *
     * <p>逐人独立处理（与预约扫描同一策略）：一个人失败不影响其余用户，
     * 否则一条脏数据会让全馆的恢复奖励停摆。
     */
    @Scheduled(cron = "0 0 3 * * MON")
    public void awardWeekly() {
        LocalDateTime weekEnd = currentMonday(LocalDate.now()).atStartOfDay();
        LocalDateTime weekStart = weekEnd.minusWeeks(1);
        int count = 0;
        for (User user : userRepository.findAll()) {
            try {
                if (creditService.awardWeeklyBonus(user.getId(), weekStart, weekEnd)) {
                    count++;
                }
            } catch (Exception e) {
                log.error("周恢复失败：userId={}", user.getId(), e);
            }
        }
        if (count > 0) {
            log.info("定时任务：{} 位用户上一自然周（{} ~ {}）无违规，信用分已恢复",
                    count, weekStart.toLocalDate(), weekEnd.toLocalDate().minusDays(1));
        }
    }

    /**
     * 取 {@code today} 所在自然周的周一（ISO 周，周一为一周之始）。
     *
     * <p>公开给用例复用：测试必须用与任务同一套周期换算，否则「上周」在用例里
     * 和在产品里不是同一个区间，断言就成了巧合。
     *
     * @param today 参照日期
     * @return 本周一
     */
    public static LocalDate currentMonday(LocalDate today) {
        return today.with(DayOfWeek.MONDAY);
    }
}

package com.libseat.unit;

import com.libseat.entity.Penalty;
import com.libseat.enums.PenaltyStatus;
import com.libseat.enums.PenaltyType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Penalty#activeAt} 的判活口径（T-V2-20 顺带修的缺陷）。
 *
 * <p>起因：S5 全量回归里 {@code CreditPenaltyApiTest} 间歇性失败 —— 刚写入的 PEAK_BAN
 * 在下一行断言里消失。原因是判活要求 {@code startTime <= now}，而写入用的墙钟与读取用的墙钟
 * 是两次取值，Windows 的时钟粒度/对时能让后者比前者小几毫秒，于是「刚刚生效」被判成「还没生效」。
 * 这不是理论风险：它在共享内存库的全量套件里真的出现过，而同一份代码单独跑就是绿的。
 */
class PenaltyActiveTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 16, 0, 0);

    private Penalty penalty(PenaltyStatus status, LocalDateTime start, LocalDateTime end) {
        return Penalty.builder()
                .userId(1L)
                .type(PenaltyType.PEAK_BAN)
                .status(status)
                .startTime(start)
                .endTime(end)
                .build();
    }

    @Test
    @DisplayName("墙钟抖动（startTime 晚几毫秒）不得让刚生效的处罚隐身")
    void toleratesClockJitter() {
        assertTrue(penalty(PenaltyStatus.ACTIVE, NOW.plusNanos(3_000_000), null).activeAt(NOW),
                "★ 3 毫秒的倒退是系统时钟的事，不是「这条处罚还没开始」");
        assertTrue(penalty(PenaltyStatus.ACTIVE, NOW, null).activeAt(NOW));
    }

    @Test
    @DisplayName("真正预约到将来生效的处罚仍然不算生效")
    void respectsScheduledStart() {
        assertFalse(penalty(PenaltyStatus.ACTIVE, NOW.plusMinutes(2), null).activeAt(NOW),
                "两分钟后才开始禁，现在就该放行");
    }

    @Test
    @DisplayName("到期即失效；无终点（学期禁 / 高峰禁）恒生效直到被解除")
    void expiryAndOpenEnded() {
        assertFalse(penalty(PenaltyStatus.ACTIVE, NOW.minusDays(1), NOW.minusSeconds(1)).activeAt(NOW));
        assertTrue(penalty(PenaltyStatus.ACTIVE, NOW.minusDays(1), NOW.plusDays(2)).activeAt(NOW));
        assertTrue(penalty(PenaltyStatus.ACTIVE, NOW.minusDays(1), null).activeAt(NOW),
                "PEAK_BAN / SEMESTER_BAN 没有 end_time，只能靠解除或回分");
    }

    @Test
    @DisplayName("已解除的处罚一律不生效")
    void releasedNeverActive() {
        assertFalse(penalty(PenaltyStatus.RELEASED, NOW.minusHours(1), null).activeAt(NOW));
    }
}

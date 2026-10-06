package com.libseat.service;

import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高峰过载降级（PRD §13.4、DESIGN §15「高峰降级」一行）。
 *
 * <p>★ 这里刻意只做"写侧自我限流"，不做读侧：PRD 要的是**优先保证「查看余座」可用**。
 *   读接口（平面图、余量、大屏）从不取许可，所以预约被打满时它们仍然直接返回 ——
 *   这也是本类不加在读路径上的原因，而不只是"读接口更快"。
 *
 * <p>超阈值时的行为是**排队等待**（最长 {@code peak.degrade-wait-ms}），等不到许可才返回
 * {@code 4291}。之所以不是"默默失败成 500"：4291 带的是"稍后重试"的可执行指令，
 * 而 500 会把一次过载记成一次故障，运营和学生对"该不该再点一次"得到完全不同的答案。
 *
 * <p>许可数取自规则中心的**实时值**（不在构造期快照），因此管理员改完立即生效；
 * 也正因为实时，把 {@code peak.degrade-max-inflight} 设成 0 就等价于一个"暂停预约"开关
 * —— 这是它被留成可配而不是写死常量的理由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingDegradeGuard {

    private static final long POLL_INTERVAL_MS = 20L;

    private final RuleConfigService ruleConfigService;

    /** 正在执行中的预约请求数。 */
    private final AtomicInteger inflight = new AtomicInteger();

    /**
     * 取到一个预约许可；等不到则抛 4291。
     *
     * <p>调用方必须在 finally 里 {@link #release()}，否则许可会被永久吃掉，
     * 表现为"过了高峰期仍然一直 4291"。
     */
    public void acquire() {
        int max = Math.max(0, ruleConfigService.degradeMaxInflight());
        long waitMs = Math.max(0L, ruleConfigService.degradeWaitMs());
        long deadline = System.nanoTime() + waitMs * 1_000_000L;
        do {
            int current = inflight.get();
            if (current < max && inflight.compareAndSet(current, current + 1)) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                int retryAfterSec = (int) Math.max(1, waitMs / 1000) + 1;
                throw new BusinessException(ErrorCode.BOOKING_DEGRADED,
                        "当前预约请求较多，你的请求已排队 " + waitMs + " 毫秒仍未排到；"
                                + "查看余座不受影响，请约 " + retryAfterSec + " 秒后重试");
            }
            sleep();
        } while (true);
    }

    /** 归还许可。 */
    public void release() {
        inflight.decrementAndGet();
    }

    /** 当前在途预约请求数（冒烟与用例用来确认"确实限流过"）。 */
    public int currentInflight() {
        return inflight.get();
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.BOOKING_DEGRADED,
                    "排队等待预约期间被中断，请重新提交预约");
        }
    }
}

package com.libseat.scheduler;

import com.libseat.service.PatrolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 巡查任务生成（DESIGN §11，10 分钟一轮）。
 *
 * <p>★ 本类与生成规则之间只有一层调用，且<b>没有任何释放能力</b>：
 * PRD §12-25 的红线是「机器不得自动释放无时间证据的座位」，所以这里唯一能写的东西是一条待处理任务。
 * 一轮失败不影响看板 —— 未处理任务最多每座一条，下一轮会自然补齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatrolTaskGenerator {

    private final PatrolService patrolService;

    /**
     * 每 10 分钟跑一轮三条自动规则。
     */
    @Scheduled(fixedRate = 600_000L, initialDelay = 60_000L)
    public void generate() {
        int created = patrolService.generate(LocalDateTime.now());
        if (created > 0) {
            log.info("定时任务：本轮新增 {} 条巡查任务待馆员处理", created);
        }
    }
}

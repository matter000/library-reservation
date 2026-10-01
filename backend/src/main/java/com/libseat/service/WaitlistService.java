package com.libseat.service;

import com.libseat.entity.Reservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 候补队列的释放挂钩点（S5 / T-V2-19 才落地队列本体）。
 *
 * <p>★ 为什么现在就存在：暂离超时、管理员强制释放、用户主动结束这三条路径都要在
 * 「座位释放的同一事务里」给候补队首发 offer。若等到 S5 再改这三条核心链路，
 * 改动面会从「新增一个服务」扩散到「重排三个事务边界」。
 * 本片按 {@code docs/TASKS-V2.md} T-V2-11 的要求保留空实现，除日志外不做任何事。
 */
@Service
@Slf4j
public class WaitlistService {

    /**
     * 座位被释放时的候补处理（当前为 no-op）。
     *
     * @param released 刚被释放的预约（提供 seatId / 日期 / 时段，S5 据此匹配候补队首）
     */
    public void onSeatReleased(Reservation released) {
        log.debug("座位已释放，候补队列为空实现，暂不做 offer：reservationId={}, seatId={}",
                released.getId(), released.getSeatId());
    }
}

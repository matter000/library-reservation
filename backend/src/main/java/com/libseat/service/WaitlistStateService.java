package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.Reservation;
import com.libseat.entity.Seat;
import com.libseat.entity.Waitlist;
import com.libseat.enums.WaitlistStatus;
import com.libseat.repository.SeatRepository;
import com.libseat.repository.WaitlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候补队列的写操作（DESIGN §8.3）。
 *
 * <p>与 {@link WaitlistService} 分开是有意的：本类被预约链的多个释放点调用
 * （取消、提前结束、爽约、暂离超时、馆员释放），如果它就是那个「带预约校验」的服务，
 * 就会形成 {@code ReservationService → Waitlist → ReservationService} 的循环依赖。
 * 于是这里只依赖仓储，谁都能安全地调它。
 *
 * <p>★ 一次释放只发一个 offer：释放出来的是<b>一个位置</b>，同时给两个人发 offer
 * 等于注定让其中一个人扑空。因此同一排队键上已有未消化的 offer 时不再重复发。
 *
 * <p>★ offer 是「轮到你尝试」而不是「给你留了座位」：窗口内座位可能被别人直接约走。
 * 确认失败时这一条立即作废并顺延下一位，队列自愈，不留幽灵 offer。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistStateService {

    private final WaitlistRepository waitlistRepository;

    private final SeatRepository seatRepository;

    private final RuleConfigService ruleConfigService;

    /**
     * 预约释放钩子：按座位所属分区 + 被让出的时段区间顶位。
     *
     * @param reservation 刚被释放（取消 / 结束 / 爽约 / 超时 / 馆员释放）的预约
     * @param now         当前时刻
     */
    @Transactional
    public void onReservationReleased(Reservation reservation, LocalDateTime now) {
        Seat seat = seatRepository.findById(reservation.getSeatId()).orElse(null);
        if (seat == null || seat.getZoneId() == null) {
            return;
        }
        onSeatReleased(seat.getZoneId(), reservation.getReserveDate(),
                reservation.getStartSlot(), reservation.getEndSlot(), now);
    }

    /**
     * 某分区在 {@code [releasedFrom, releasedTo)} 区间里让出了一个位置：给队首发 offer。
     *
     * <p>匹配条件是「排队起始格落在这段区间内」而不是「起始格完全相等」：
     * 9:00-11:00 的预约取消后，10:00 起排队的人同样能坐下。只按相等匹配，
     * 长时段释放出来的位置就永远叫不醒短时段排队的人。
     *
     * @param zoneId      分区
     * @param date        日期
     * @param releasedFrom 让出区间的起始格（含）
     * @param releasedTo   让出区间的结束格（不含）
     * @param now         当前时刻
     * @return 本次是否发出了 offer
     */
    @Transactional
    public boolean onSeatReleased(Long zoneId, LocalDate date, int releasedFrom, int releasedTo,
                                  LocalDateTime now) {
        if (zoneId == null || date == null || releasedTo <= releasedFrom) {
            return false;
        }
        List<Waitlist> waiting = waitlistRepository
                .findByZoneIdAndReserveDateAndStatusOrderByIdAsc(zoneId, date, WaitlistStatus.WAITING);
        for (Waitlist candidate : waiting) {
            if (candidate.getStartSlot() < releasedFrom || candidate.getStartSlot() >= releasedTo) {
                continue;
            }
            if (!BizConstants.slotStart(date, candidate.getStartSlot()).isAfter(now)) {
                // 起始格已经开始甚至结束：这人再怎么确认都会被预约链第 5 步拒掉，
                // 发 offer 只是浪费一个位置并让他以为轮到自己
                continue;
            }
            if (hasOutstandingOffer(candidate)) {
                // 这个键上已经有人拿着 offer，位置不能被同时许诺两次
                continue;
            }
            offer(candidate, now);
            return true;
        }
        return false;
    }

    /**
     * 把一条候补顶到 OFFERED（只在本类的顶位路径里调用）。
     *
     * @param waitlist 队列记录
     * @param now      当前时刻
     */
    private void offer(Waitlist waitlist, LocalDateTime now) {
        waitlist.changeStatus(WaitlistStatus.OFFERED);
        waitlist.setOfferedTime(now);
        waitlist.setOfferExpireTime(now.plusMinutes(ruleConfigService.waitlistOfferMin()));
        waitlistRepository.save(waitlist);
        // TODO T-V2-20 通知中心：写一条 WAITLIST_OFFER 站内通知（t_notice 随通知任务一起建）
        log.info("候补 offer 已发出：waitlistId={}, userId={}, 截止 {}",
                waitlist.getId(), waitlist.getUserId(), waitlist.getOfferExpireTime());
    }

    /**
     * 过期未确认：置 EXPIRED 并把同一个位置顺延给下一位。<b>不记违规</b>。
     *
     * <p>★ 为什么是 {@code REQUIRES_NEW}：确认失败时调用方（{@link WaitlistService#confirm}）
     * 紧接着就抛异常，外层事务整个回滚。若顺延也一起回滚，那条 offer 会留在队列里
     * 直到下一轮扫描 —— 用户看到的正是「我确认失败了，但位置也没给下一个人」。
     * 自愈必须与「失败的下单」解耦，所以单独提交。
     *
     * <p>★ 参数是 ID 而不是实体：外层上下文里同一个 ID 有自己的托管副本，
     * 若在两个事务里各改各的副本，回滚时会把已提交的状态覆盖回去。按 ID 在新上下文里重新装载，
     * 两边互不污染。
     *
     * <p>这里不调「释放钩子」而是显式取该键的下一个队首：offer 过期时座位其实一直没被占，
     * 走释放钩子会被「同键已有未消化 offer」的判断绕开。
     *
     * @param waitlistId 队列记录 ID
     * @param now        当前时刻
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireAndPromote(Long waitlistId, LocalDateTime now) {
        Waitlist waitlist = require(waitlistId);
        waitlist.changeStatus(WaitlistStatus.EXPIRED);
        waitlistRepository.save(waitlist);
        List<Waitlist> waiting = waitlistRepository
                .findByZoneIdAndReserveDateAndStartSlotAndStatusOrderByIdAsc(
                        waitlist.getZoneId(), waitlist.getReserveDate(),
                        waitlist.getStartSlot(), WaitlistStatus.WAITING);
        for (Waitlist next : waiting) {
            if (!BizConstants.slotStart(next.getReserveDate(), next.getStartSlot()).isAfter(now)) {
                continue;
            }
            offer(next, now);
            return;
        }
    }

    /**
     * 一轮过期扫描（供调度器调用）。
     *
     * @param now 当前时刻
     * @return 本轮过期的条数
     */
    @Transactional
    public int expireOverdue(LocalDateTime now) {
        List<Waitlist> overdue = waitlistRepository
                .findByStatusAndOfferExpireTimeBefore(WaitlistStatus.OFFERED, now);
        for (Waitlist waitlist : overdue) {
            expireAndPromote(waitlist.getId(), now);
        }
        return overdue.size();
    }

    /**
     * 确认成功：置 CONFIRMED。
     *
     * <p>与 {@link #expireAndPromote} 相反，这里刻意留在调用方事务里：预约刚在同一个事务里建成，
     * 两边必须同生同死，否则会出现「座位已到手但队列还占着名额」或反之。
     *
     * @param waitlistId 队列记录 ID
     */
    @Transactional
    public void markConfirmed(Long waitlistId) {
        Waitlist waitlist = require(waitlistId);
        waitlist.changeStatus(WaitlistStatus.CONFIRMED);
        waitlistRepository.save(waitlist);
        log.info("候补确认成功：waitlistId={}, userId={}", waitlist.getId(), waitlist.getUserId());
    }

    /**
     * 本人退出队列。
     *
     * @param waitlistId 队列记录 ID
     */
    @Transactional
    public void markCancelled(Long waitlistId) {
        Waitlist waitlist = require(waitlistId);
        waitlist.changeStatus(WaitlistStatus.CANCELLED);
        waitlistRepository.save(waitlist);
    }

    /**
     * offer 是否已过确认窗口。
     *
     * @param waitlist 队列记录
     * @param now      当前时刻
     * @return 是否已过期
     */
    public boolean offerExpired(Waitlist waitlist, LocalDateTime now) {
        return waitlist.getOfferExpireTime() != null && !waitlist.getOfferExpireTime().isAfter(now);
    }

    /**
     * 距 offer 过期还剩多少分钟（负数表示已过）。
     *
     * @param waitlist 队列记录
     * @param now      当前时刻
     * @return 分钟数，无 offer 时为 null
     */
    public Integer minutesToExpire(Waitlist waitlist, LocalDateTime now) {
        if (waitlist.getOfferExpireTime() == null) {
            return null;
        }
        return (int) Duration.between(now, waitlist.getOfferExpireTime()).toMinutes();
    }

    /** 该排队键上是否已有未消化的 offer。 */
    private boolean hasOutstandingOffer(Waitlist waitlist) {
        return !waitlistRepository.findByZoneIdAndReserveDateAndStartSlotAndStatusOrderByIdAsc(
                waitlist.getZoneId(), waitlist.getReserveDate(),
                waitlist.getStartSlot(), WaitlistStatus.OFFERED).isEmpty();
    }

    /** 按 ID 取记录（在新上下文里重新装载，不与调用方的托管副本共用）。 */
    private Waitlist require(Long waitlistId) {
        return waitlistRepository.findById(waitlistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "候补记录不存在"));
    }
}

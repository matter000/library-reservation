package com.libseat.repository;

import com.libseat.entity.Waitlist;
import com.libseat.enums.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候补队列数据访问层。
 *
 * <p>★ 一律用「等值条件」的派生查询，不写 {@code where (:x is null or ...)} 这类可选参数 ——
 * H2/MySQL 对 {@code NULL} 参数的比较结果不同，S3 的申诉队列已经为此改过一次。
 * 活动名额靠 {@code active_flag = 1} 过滤（终态是 NULL，天然选不到）。
 */
@Repository
public interface WaitlistRepository extends JpaRepository<Waitlist, Long> {

    /** 活动名额占用的 flag 值（终态为 null）。 */
    Integer ACTIVE_FLAG = 1;

    /**
     * 某个排队键上的队列（按加入先后，队首在前）。
     *
     * @param zoneId    分区
     * @param date      日期
     * @param startSlot 起始格
     * @param status    状态
     * @return 队列
     */
    List<Waitlist> findByZoneIdAndReserveDateAndStartSlotAndStatusOrderByIdAsc(
            Long zoneId, LocalDate date, int startSlot, WaitlistStatus status);

    /**
     * 某人的活动候补（新的在前）。
     *
     * @param userId     用户
     * @param activeFlag 活动标记（传 {@link #ACTIVE_FLAG}）
     * @return 列表
     */
    List<Waitlist> findByUserIdAndActiveFlagOrderByIdDesc(Long userId, Integer activeFlag);

    /**
     * 某人占用的活动名额数。
     *
     * @param userId     用户
     * @param activeFlag 活动标记
     * @return 条数
     */
    long countByUserIdAndActiveFlag(Long userId, Integer activeFlag);

    /**
     * 某人在某排队键上的活动候补（重复排队判定）。
     *
     * @param userId     用户
     * @param zoneId     分区
     * @param date       日期
     * @param startSlot  起始格
     * @param activeFlag 活动标记
     * @return 活动记录，无为 empty
     */
    List<Waitlist> findByUserIdAndZoneIdAndReserveDateAndStartSlotAndActiveFlag(
            Long userId, Long zoneId, LocalDate date, int startSlot, Integer activeFlag);

    /**
     * 某分区某日的全部等待者（按加入先后，跨所有排队键）。
     *
     * <p>释放一个长区间时，起始格落在区间内部的短时段等待者同样坐得下，
     * 所以匹配要覆盖整个区间，而不是只找完全相等的那个键。
     *
     * @param zoneId 分区
     * @param date   日期
     * @param status 状态
     * @return 队列（队首在前）
     */
    List<Waitlist> findByZoneIdAndReserveDateAndStatusOrderByIdAsc(
            Long zoneId, LocalDate date, WaitlistStatus status);

    /**
     * 已过确认窗口的 offer（顺延任务用）。
     *
     * @param status  状态（OFFERED）
     * @param deadline 截止时刻上界
     * @return 待过期记录
     */
    List<Waitlist> findByStatusAndOfferExpireTimeBefore(WaitlistStatus status, LocalDateTime deadline);
}

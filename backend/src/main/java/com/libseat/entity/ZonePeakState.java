package com.libseat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 分区动态高峰状态（t_zone_peak_state）—— 由 {@code PeakDetectTask} 每 5 分钟 UPSERT 一行。
 *
 * <p>主键就是 {@code zoneId}：一个分区同时最多一条状态，因此不需要清理任务，
 * 也不会随运行时间长出多余行。这里只存「当前状态 + 首次超阈时刻」，
 * 不存采样流水 —— 占用率的判定要跨采样点看连续性（所以留 {@code sinceTime}），
 * 但消费方只问「此刻算不算高峰」（所以留 {@code untilTime}）。
 *
 * <p>★ 占用率的真相仍然只有 {@code t_reservation_slot} 一处：本表记的是判定结果，
 * 不是第二份占用数据（S4 定下的约束）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_zone_peak_state")
public class ZonePeakState {

    /** 分区 ID（同时是主键）。 */
    @Id
    @Column(name = "zone_id")
    private Long zoneId;

    /** 首次连续超阈时刻；占用率回落到阈值以下即置 null。 */
    @Column(name = "since_time")
    private LocalDateTime sinceTime;

    /** 动态高峰生效到何时；为 null 或已过表示当前不在高峰。 */
    @Column(name = "until_time")
    private LocalDateTime untilTime;

    /** 最近一次采样的占用率（百分数，0~100），供看板和排查。 */
    @Column(name = "ratio")
    private Double ratio;

    /** 最近一次采样时刻。 */
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    /** 落库时间由实体自己维护（与其他实体一致，不依赖 JPA Auditing）。 */
    @PrePersist
    @PreUpdate
    void touch() {
        updateTime = LocalDateTime.now();
    }

    /**
     * 此刻是否处于动态高峰。
     *
     * <p>★ 判定只在这里做一次：别处再比一遍时间，就会出现「面板显示高峰、校验说不高峰」。
     *
     * @param now 当前时刻
     * @return 是否生效中
     */
    public boolean activeAt(LocalDateTime now) {
        return untilTime != null && untilTime.isAfter(now);
    }
}

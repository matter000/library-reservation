package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.entity.Seat;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.ReservationStatus;
import com.libseat.enums.SeatDisplayStatus;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ 占用派生核心（全局唯一入口）。
 *
 * <p>座位占用状态不落库，由 {@code t_reservation_slot} 按「日期 + 时段」实时派生。
 * 平面图 / 楼层空闲数 / 使用情况 / 统计利用率全部复用本类，杜绝派生逻辑双写。
 *
 * <p>V2 起派生结果带上暂离信息（{@link SeatOccupancy}）：整层两次查询（占用 + OPEN 暂离），
 * 不在座位循环里逐条查库。
 */
@Service
@RequiredArgsConstructor
public class OccupancyService {

    /** 会占用座位的状态集合（只有 CONFIRMED 与 CHECKED_IN）。 */
    public static final List<ReservationStatus> OCCUPYING_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final ReservationSlotRepository reservationSlotRepository;

    private final LeaveRecordRepository leaveRecordRepository;

    /**
     * 某座位在「某日期某时段」的占用快照。
     *
     * @param status     占用预约的状态
     * @param leaveStart 该预约 OPEN 暂离的开始时刻，无暂离为 null
     * @param leaveUntil 该预约 OPEN 暂离的应归时刻，无暂离为 null
     */
    public record SeatOccupancy(ReservationStatus status, LocalDateTime leaveStart, LocalDateTime leaveUntil) {

        /**
         * 该暂离是否已覆盖到给定的时段格。
         *
         * <p>按「格」对齐而不是按时刻比：人 17:59 离座，当前格 17:30-18:00 就该显示暂离，
         * 而 19:00 之后（超出应归时刻）是否仍是暂离由超时扫描决定，不在这里猜。
         *
         * @param slot 时段序号
         * @return 该格是否处于暂离中
         */
        boolean leavingAt(int slot) {
            return leaveStart != null && BizConstants.slotOf(leaveStart.toLocalTime()) <= slot;
        }
    }

    /**
     * 取得「某日期某时段」的全部有效占用（含 OPEN 暂离）。
     *
     * <p>只查两次库，返回 {@code seatId -> SeatOccupancy}。
     *
     * @param date 日期
     * @param slot 时段序号
     * @return 座位 ID → 占用快照
     */
    @Transactional(readOnly = true)
    public Map<Long, SeatOccupancy> getOccupancy(LocalDate date, int slot) {
        Map<Long, SeatOccupancy> map = new HashMap<>();
        if (date == null) {
            return map;
        }
        List<Object[]> rows = reservationSlotRepository.findOccupiedByDateAndSlot(date, slot, OCCUPYING_STATUSES);
        for (Object[] row : rows) {
            Object seatId = row[0];
            Object status = row[1];
            if (seatId == null || status == null) {
                continue;
            }
            map.put(((Number) seatId).longValue(),
                    new SeatOccupancy((ReservationStatus) status, null, null));
        }
        // 暂离是「已签到之后」才可能出现的态，因此只给已在场的座位补标记
        for (Object[] row : leaveRecordRepository.findOpenLeavesByDateAndSlot(
                date, slot, LeaveStatus.OPEN_FLAG)) {
            if (row[0] == null) {
                continue;
            }
            long seatId = ((Number) row[0]).longValue();
            SeatOccupancy occupancy = map.get(seatId);
            if (occupancy != null && occupancy.status() == ReservationStatus.CHECKED_IN) {
                map.put(seatId, new SeatOccupancy(occupancy.status(),
                        (LocalDateTime) row[1], (LocalDateTime) row[2]));
            }
        }
        return map;
    }

    /**
     * 派生座位的展示态（DESIGN §6.1）。
     *
     * <ol>
     *   <li>楼层 CLOSED 或 seat.status ≠ AVAILABLE → UNAVAILABLE；</li>
     *   <li>无有效预约 → FREE；</li>
     *   <li>CONFIRMED → RESERVED；</li>
     *   <li>CHECKED_IN 且该时段已落在 OPEN 暂离窗口内 → ON_LEAVE；</li>
     *   <li>CHECKED_IN → IN_USE。</li>
     * </ol>
     *
     * <p>★ 暂离只改「人是否在座」，不改占用：slot 行不删，座位依然不可被他人预约。
     *
     * @param seat        座位
     * @param floorClosed 楼层是否已关闭
     * @param occupancy   该时段的占用快照
     * @param slot        查询时段序号（暂离按格对齐判定，快照本身已按日期过滤）
     * @return 展示态
     */
    public SeatDisplayStatus derive(Seat seat, boolean floorClosed,
                                    Map<Long, SeatOccupancy> occupancy, int slot) {
        if (floorClosed || seat.getStatus() != com.libseat.enums.SeatStatus.AVAILABLE) {
            return SeatDisplayStatus.UNAVAILABLE;
        }
        SeatOccupancy found = occupancy.get(seat.getId());
        if (found == null) {
            return SeatDisplayStatus.FREE;
        }
        if (found.status() == ReservationStatus.CHECKED_IN) {
            return found.leavingAt(slot) ? SeatDisplayStatus.ON_LEAVE : SeatDisplayStatus.IN_USE;
        }
        if (found.status() == ReservationStatus.CONFIRMED) {
            return SeatDisplayStatus.RESERVED;
        }
        return SeatDisplayStatus.FREE;
    }

    /**
     * 一组座位在某个时段的占用统计。
     *
     * <p>★ 余量卡片、动态高峰判定、平面图统计必须走这一处：占用率算两遍必然出现
     *   「卡片显示 85%，系统却按 92% 压时长」，而高峰是不是成立恰恰取决于这个百分比。
     *
     * @param total       座位总数
     * @param occupied    正在被占用的座位数（含暂离）
     * @param unavailable 维护 / 停用（含楼层或分区关闭）座位数
     */
    public record ZoneUsage(long total, long occupied, long unavailable) {

        /** 可正常使用座位数。 */
        public long usable() {
            return total - unavailable;
        }

        /** 占用率（百分数，一位小数）。 */
        public double rate() {
            return BizConstants.rate(occupied, usable());
        }
    }

    /**
     * 按 {@link #derive} 逐座统计占用情况。
     *
     * @param seats       座位列表
     * @param closed      楼层 / 分区是否关闭（关闭即全部不可用）
     * @param occupancy   该时段的占用快照
     * @param slot        时段序号
     * @return 占用统计
     */
    public ZoneUsage usageOf(List<Seat> seats, boolean closed,
                             Map<Long, SeatOccupancy> occupancy, int slot) {
        long occupied = 0;
        long unavailable = 0;
        for (Seat seat : seats) {
            SeatDisplayStatus display = derive(seat, closed, occupancy, slot);
            if (display == SeatDisplayStatus.UNAVAILABLE) {
                unavailable++;
            } else if (display.occupying()) {
                occupied++;
            }
        }
        return new ZoneUsage(seats.size(), occupied, unavailable);
    }
}

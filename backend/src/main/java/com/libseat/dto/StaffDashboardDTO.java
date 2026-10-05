package com.libseat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 馆员监控大屏 DTO（DESIGN §12 / TASKS-V2 T-V2-22）。
 *
 * <p>★ 这个接口只做「聚合」，不做第二套派生：占用数字全部来自
 * {@code AdminStatisticsService}（其内部走 {@code OccupancyService.usageOf/derive} 这一唯一派生入口），
 * 异常数字来自各自的看板服务。若在这里重算一遍「谁在座」，
 * V2 从 S1 起守的「占用只从 t_reservation_slot 派生」就出现第二个真相源了。
 *
 * <p>★ 热力用的是**今日各时段的预约数**（`heat`），不是实时在座数：
 * 后者要按 44 个时段各跑一次派生，代价大且对馆员没有额外信息 ——
 * 现在这一格由 {@code overview}/{@code floors} 给，未来这一格由预约数给形状。
 * 前端必须按这个含义标注，不能写成「占用」。
 */
public class StaffDashboardDTO {

    /**
     * 大屏整页数据（单接口返回，前端 30 秒刷一次）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardVO {

        /** 生成时刻（yyyy-MM-dd HH:mm:ss）。 */
        private String generatedAt;

        /** 统计日期。 */
        private String date;

        /** 当前时段序号。 */
        private Integer slotIndex;

        /** 当前时段文案，如 18:00-18:30。 */
        private String slotLabel;

        /** 今天是否闭馆（闭馆时占用为 0 不是异常，前端要整体置灰）。 */
        private Boolean closedToday;

        /** 全馆概览（与管理员统计页同一份口径）。 */
        private StatsDTO.OverviewVO overview;

        /** 此刻暂离保留中的座位数（这些椅子仍被占着，但人不欠违规）。 */
        private Long onLeave;

        /** 楼层 → 分区占用（与管理员统计页同一份口径）。 */
        private List<StatsDTO.FloorUsageVO> floors;

        /** 今日各时段预约数（热力形状，含义见类注释）。 */
        private List<StatsDTO.SlotHeatVO> heat;

        /** 需要人来处理的异常。 */
        private AnomalyVO anomalies;
    }

    /**
     * 异常计数。
     *
     * <p>★ 每一项都只回答「现在有几件事等人处理」，判定规则留在各自的服务里：
     * 大屏若自己写一遍「什么算待处理」，馆员看到的数字就会和看板点开的不一致。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnomalyVO {

        /** 未处理巡查任务总数。 */
        private Long patrolOpen;

        /** 未处理巡查任务·高。 */
        private Long patrolHigh;

        /** 未处理巡查任务·中。 */
        private Long patrolMid;

        /** 未处理巡查任务·低。 */
        private Long patrolLow;

        /** 最久一条未处理任务已经等了多久（分钟）；无任务为 0。 */
        private Long patrolOldestMinutes;

        /** 待复核申诉数。 */
        private Long appealsPending;

        /** 已升级终审的申诉数。 */
        private Long appealsEscalated;

        /** 此刻真正生效中的处罚人数（按 {@code Penalty#activeAt} 判活）。 */
        private Long activePenaltyUsers;

        /** 当前处于高峰的分区数。 */
        private Long peakZones;
    }
}

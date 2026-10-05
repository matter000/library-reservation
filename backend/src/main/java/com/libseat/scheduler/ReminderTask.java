package com.libseat.scheduler;

import com.libseat.common.BizConstants;
import com.libseat.entity.LeaveRecord;
import com.libseat.entity.Reservation;
import com.libseat.enums.LeaveStatus;
import com.libseat.enums.NoticeType;
import com.libseat.enums.ReservationStatus;
import com.libseat.repository.LeaveRecordRepository;
import com.libseat.repository.ReservationRepository;
import com.libseat.service.NoticeService;
import com.libseat.service.RuleConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 站内提醒任务（DESIGN §8.4，T-V2-20）：签到窗口开启 / 暂离即将到期。
 *
 * <p>★ 通知不是判定，判定也不是通知。这里只回答「现在值得打扰用户一次吗」，
 * 而能不能签到、超时怎么罚，仍由业务链自己算。因此本任务停摆不会造成错账，
 * 只会让人少看一条消息 —— 这也是为什么提醒窗口取得比触发点宽：补跑要还能发出去，
 * 重复由 {@code uk_notice_ref} 拦住。
 *
 * <p>逐条 try-catch：一条脏数据（比如预约已经没了的暂离记录）不能挡住同轮其他人的提醒，
 * 与 {@link ReservationScheduler} 同一套做法。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTask {

    /** 时刻文案格式。 */
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final ReservationRepository reservationRepository;

    private final LeaveRecordRepository leaveRecordRepository;

    private final RuleConfigService ruleConfigService;

    private final NoticeService noticeService;

    /**
     * 每分钟扫一轮两类提醒。
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 55_000L)
    public void sendReminders() {
        sendReminders(LocalDateTime.now());
    }

    /**
     * 以给定时刻扫一轮（用例入口）。
     *
     * <p>★ 为什么暴露时刻参数：提醒的成立条件完全由「预约的签到窗口」和「暂离的应归时刻」决定，
     * 而真实时钟要么让窗口还没开、要么让用例只能在某几个小时里跑。把时刻交出来，
     * 判定逻辑一行没变，用例却能在任何时刻确定性地验证「该发的那条发了、不该发的没发」。
     * 日期过滤也跟着用这个时刻，所以用例传的必须是预约当天附近的时刻。
     *
     * @param now 判定时刻
     */
    public void sendReminders(LocalDateTime now) {
        int count = checkInReminders(now) + leaveReminders(now);
        if (count > 0) {
            log.info("定时任务：本轮发出 {} 条站内提醒", count);
        }
    }

    /**
     * 签到窗口已开且还没到爽约判定时刻的预约 → 提醒。
     *
     * <p>窗口取「开起到签到截止」整段而不是「开启那一分钟」：应用重启、上一轮异常都会让
     * 恰好落在两轮之间的人永远收不到，而迟到提醒仍然有用（去重保证只发一次）。
     *
     * @return 本轮提醒条数
     */
    private int checkInReminders(LocalDateTime now) {
        List<Reservation> candidates = reservationRepository.findByStatusAndDateRange(
                ReservationStatus.CONFIRMED, now.toLocalDate().minusDays(1), now.toLocalDate());
        int beforeMin = ruleConfigService.checkInBeforeMin();
        int afterMin = ruleConfigService.checkInAfterMin();
        int sent = 0;
        for (Reservation reservation : candidates) {
            try {
                LocalDateTime start = BizConstants.slotStart(
                        reservation.getReserveDate(), reservation.getStartSlot());
                LocalDateTime openAt = start.minusMinutes(beforeMin);
                LocalDateTime deadline = start.plusMinutes(afterMin);
                if (now.isBefore(openAt) || !now.isBefore(deadline)) {
                    continue;
                }
                boolean group = reservation.getGroupId() != null;
                noticeService.push(reservation.getUserId(), NoticeType.CHECK_IN_REMIND,
                        "签到窗口已开启",
                        BizConstants.formatDate(reservation.getReserveDate()) + " "
                                + BizConstants.rangeLabel(reservation.getStartSlot(),
                                BizConstants.hoursOfSlots(reservation.getEndSlot() - reservation.getStartSlot()))
                                + " 的预约现在可以签到了，" + HM.format(deadline) + " 前有效。"
                                + (group
                                ? "组队预约由组长签到即视为全队签到，成员请在组长签到后点「我已入座」。"
                                : "超时未签到会记一次爽约并扣信用分，座位同时放给候补队列。"),
                        NoticeService.REF_RESERVATION, reservation.getId(), deadline);
                sent++;
            } catch (Exception e) {
                log.error("签到提醒失败：reservationId={}", reservation.getId(), e);
            }
        }
        return sent;
    }

    /**
     * 还剩 N 分钟内到期的 OPEN 暂离 → 提醒（同一暂离记录只发一次）。
     *
     * @return 本轮提醒条数
     */
    private int leaveReminders(LocalDateTime now) {
        int remindMin = ruleConfigService.noticeLeaveRemindMin();
        List<LeaveRecord> due = leaveRecordRepository.findByStatusAndOpenFlagAndPlannedEndTimeBetween(
                LeaveStatus.OPEN, LeaveStatus.OPEN_FLAG, now, now.plusMinutes(remindMin));
        int sent = 0;
        for (LeaveRecord record : due) {
            try {
                noticeService.push(record.getUserId(), NoticeType.LEAVE_EXPIRE,
                        "暂离即将到期",
                        "请在 " + HM.format(record.getPlannedEndTime()) + " 前返回并点「我已返回」"
                                + "（还剩 " + Math.max(0, Duration.between(now,
                                record.getPlannedEndTime()).toMinutes()) + " 分钟）。"
                                + "超时未归会记一次违规、释放座位，且不顺延剩余使用时间。",
                        NoticeService.REF_LEAVE, record.getId(), record.getPlannedEndTime());
                sent++;
            } catch (Exception e) {
                log.error("暂离到期提醒失败：leaveId={}", record.getId(), e);
            }
        }
        return sent;
    }
}

package com.libseat.unit;

import com.libseat.common.BizConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务常量与时段工具方法的单元测试（DESIGN-V2 §4）。
 *
 * <p>覆盖：30 分钟格编码往返、开放窗口边界、时长 1~4 小时、签到窗口、手机号脱敏、比率计算。
 * 格长改造后，时段合法性判断的是「能否作为起始格」，结束边界由开放档案给出。
 */
class BizConstantsTest {

    @ParameterizedTest
    @ValueSource(ints = {14, 15, 16, 24, 43, 44, 45})
    @DisplayName("合法起始时段序号（网格 07:00~22:30）应通过校验")
    void validSlotIndex_shouldAccept(int slot) {
        assertTrue(BizConstants.validSlotIndex(slot), "slot=" + slot + " 应合法");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 13, 46, 99})
    @DisplayName("越界时段序号应被拒绝（13=06:30 早于网格，46=23:00 是开区间边界不可作为起始）")
    // ★ 网格比任何一档窗口都宽：档案能不能约到某一格，由 OpenRuleApiTest 的下界/边界用例判，
    //   这里只管"这个格号存不存在"。别把两者又合回一对常量。
    void validSlotIndex_shouldReject(int slot) {
        assertFalse(BizConstants.validSlotIndex(slot), "slot=" + slot + " 应非法");
    }

    @ParameterizedTest
    @CsvSource({"08:00,16", "08:30,17", "14:00,28", "21:30,43", "22:00,44", "00:00,0", "23:30,47"})
    @DisplayName("slot 编码往返：时间 → 格 → 时间 必须一致")
    void slotEncoding_roundTrip(String text, int expectedSlot) {
        java.time.LocalTime time = java.time.LocalTime.parse(text);
        int slot = BizConstants.slotOf(time);
        assertEquals(expectedSlot, slot, text + " 应编码为 " + expectedSlot);
        assertEquals(time, BizConstants.timeOfSlot(slot), "格 " + slot + " 应还原为 " + text);
        assertEquals(text, BizConstants.slotText(slot), "格 " + slot + " 的文本");
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,4", "4,8"})
    @DisplayName("小时 → 格数换算（duration 仍以小时表达）")
    void slotsOf_shouldDoubleHours(int hours, int expectedSlots) {
        assertEquals(expectedSlots, BizConstants.slotsOf(hours));
    }

    @Test
    @DisplayName("半小时格标签：28 格 = 14:00-14:30，区间标签按格数推进")
    void labels_shouldUseHalfHours() {
        assertEquals("14:00-14:30", BizConstants.slotLabel(28));
        assertEquals("14:30-15:00", BizConstants.slotLabel(29));
        assertEquals("14:00-16:00", BizConstants.rangeLabel(28, 2));
        assertEquals("08:00-22:00 共 28 格", "08:00-22:00 共 " + BizConstants.SLOT_COUNT + " 格");
    }

    @Test
    @DisplayName("V1 语义对照：V1 的第 h 小时 == V2 的第 2h 格，且一小时恰为两个连续格")
    void v1SlotShouldMapToDoubledSlotWithSameMeaning() {
        for (int v1Hour = 8; v1Hour <= 21; v1Hour++) {
            int v2Slot = v1Hour * BizConstants.SLOTS_PER_HOUR;
            String expectedStart = String.format("%02d:00", v1Hour);
            assertTrue(BizConstants.slotLabel(v2Slot).startsWith(expectedStart),
                    "V1 小时 " + v1Hour + " 换算后标签起点必须仍是 " + expectedStart
                            + "，实际：" + BizConstants.slotLabel(v2Slot));
            // V1 的一小时恰好被拆成 V2 的两个连续格，且两格首尾相接、总跨度不变
            assertEquals(expectedStart + "-" + BizConstants.slotText(v2Slot + 1),
                    BizConstants.slotLabel(v2Slot));
            assertEquals(BizConstants.slotText(v2Slot + 1),
                    BizConstants.slotLabel(v2Slot + 1).substring(0, 5),
                    "第 2 格必须紧接第 1 格，中间不留缝");
            assertEquals(String.format("%02d:00", v1Hour + 1),
                    BizConstants.slotText(v2Slot + 2),
                    "两格走完应正好等于 V1 的一小时");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("时长 1~4 应通过校验")
    void validDuration_shouldAccept(int duration) {
        assertTrue(BizConstants.validDuration(duration));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 6, 100})
    @DisplayName("时长 0/5/6/100 应被拒绝")
    void validDuration_shouldReject(int duration) {
        assertFalse(BizConstants.validDuration(duration));
    }

    @Test
    @DisplayName("duration 为 null 应被拒绝（不是 NPE）")
    void validDuration_nullShouldReject() {
        assertFalse(BizConstants.validDuration(null));
    }

    @ParameterizedTest
    @CsvSource({"0,14", "13,14", "46,45", "50,45", "30,30"})
    @DisplayName("clampToSlotRange 应把时段序号收敛到网格 [14,45]")
    void clampToSlotRange(int input, int expected) {
        assertEquals(expected, BizConstants.clampToSlotRange(input));
    }

    @Test
    @DisplayName("可预约窗口应为 7 天（今天 ~ 今天+6）")
    void advanceDays() {
        assertEquals(7, BizConstants.ADVANCE_DAYS);
        assertEquals(28, BizConstants.SLOT_COUNT);
        assertEquals(2, BizConstants.DEFAULT_DURATION);
        assertEquals(15, BizConstants.CHECK_IN_BEFORE_MIN);
        assertEquals(15, BizConstants.CHECK_IN_AFTER_MIN);
    }

    @Test
    @DisplayName("slotStart / slotLabel / rangeLabel 的文本格式（30 分钟格）")
    void slotText() {
        LocalDate date = LocalDate.of(2026, 2, 11);
        assertEquals(LocalDateTime.of(2026, 2, 11, 14, 0), BizConstants.slotStart(date, 28));
        assertEquals(LocalDateTime.of(2026, 2, 11, 14, 30), BizConstants.slotStart(date, 29));
        assertEquals("14:00-14:30", BizConstants.slotLabel(28));
        assertEquals("14:00-16:00", BizConstants.rangeLabel(28, 2));
        assertEquals("08:00", BizConstants.slotText(16));
        assertEquals("08:30", BizConstants.slotText(17));
        assertEquals("2026-02-11", BizConstants.formatDate(date));
    }

    @ParameterizedTest
    @CsvSource({"13812345671,138****5671", "13800000000,138****0000"})
    @DisplayName("手机号脱敏：保留前 3 后 4")
    void maskPhone(String raw, String expected) {
        assertEquals(expected, BizConstants.maskPhone(raw));
    }

    @Test
    @DisplayName("手机号为空 / 过短不应抛异常")
    void maskPhone_edge() {
        assertEquals("", BizConstants.maskPhone(null));
        assertEquals("", BizConstants.maskPhone("  "));
        assertEquals("138", BizConstants.maskPhone("138"), "长度不足 7 位时原样返回");
        assertEquals("1234567", BizConstants.maskPhone("1234567"), "长度恰好 7 位时原样返回");
        assertEquals("138****4567", BizConstants.maskPhone("1381234567"), "长度 10 位应脱敏");
    }

    @Test
    @DisplayName("rate：分母为 0 返回 0，保留一位小数")
    void rate() {
        assertEquals(0.0, BizConstants.rate(5, 0));
        assertEquals(0.0, BizConstants.rate(5, -1));
        assertEquals(50.0, BizConstants.rate(1, 2));
        assertEquals(33.3, BizConstants.rate(1, 3));
        assertEquals(66.7, BizConstants.rate(2, 3));
        assertEquals(100.0, BizConstants.rate(3, 3));
    }
}

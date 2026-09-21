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
 * 业务常量与时段工具方法的单元测试（DESIGN §8.4）。
 *
 * <p>覆盖：开放时段 8~21、时长 1~4、日期窗口、签到窗口、手机号脱敏、比率计算。
 */
class BizConstantsTest {

    @ParameterizedTest
    @ValueSource(ints = {8, 9, 12, 20, 21})
    @DisplayName("合法时段序号 8~21 应通过校验")
    void validSlotIndex_shouldAccept(int slot) {
        assertTrue(BizConstants.validSlotIndex(slot), "slot=" + slot + " 应合法");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 7, 22, 23, 99})
    @DisplayName("越界时段序号应被拒绝")
    void validSlotIndex_shouldReject(int slot) {
        assertFalse(BizConstants.validSlotIndex(slot), "slot=" + slot + " 应非法");
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
    @CsvSource({"7,8", "0,8", "22,21", "23,21", "14,14"})
    @DisplayName("clampToSlotRange 应把小时收敛到 [8,21]")
    void clampToSlotRange(int input, int expected) {
        assertEquals(expected, BizConstants.clampToSlotRange(input));
    }

    @Test
    @DisplayName("可预约窗口应为 7 天（今天 ~ 今天+6）")
    void advanceDays() {
        assertEquals(7, BizConstants.ADVANCE_DAYS);
        assertEquals(14, BizConstants.SLOT_COUNT);
        assertEquals(2, BizConstants.DEFAULT_DURATION);
        assertEquals(15, BizConstants.CHECK_IN_BEFORE_MIN);
        assertEquals(15, BizConstants.CHECK_IN_AFTER_MIN);
    }

    @Test
    @DisplayName("slotStart / slotLabel / rangeLabel 的文本格式")
    void slotText() {
        LocalDate date = LocalDate.of(2026, 2, 11);
        assertEquals(LocalDateTime.of(2026, 2, 11, 14, 0), BizConstants.slotStart(date, 14));
        assertEquals("14:00-15:00", BizConstants.slotLabel(14));
        assertEquals("14:00-16:00", BizConstants.rangeLabel(14, 2));
        assertEquals("08:00", BizConstants.hourText(8));
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

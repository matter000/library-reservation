package com.libseat.enums;

import lombok.Getter;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 日型（开放档案的分组维度）。
 *
 * <p>只区分工作日 / 周末 / 节假日三类；具体某天的例外由
 * {@code t_open_calendar} 覆盖，不在此枚举里表达。
 */
@Getter
public enum DayType {

    /** 工作日。 */
    WORKDAY("WORKDAY", "工作日"),

    /** 周末。 */
    WEEKEND("WEEKEND", "周末"),

    /** 节假日。 */
    HOLIDAY("HOLIDAY", "节假日");

    private final String code;

    private final String text;

    DayType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * 按星期推断默认日型（周六、周日为周末）。
     *
     * <p>节假日需要外部日历数据，这里不猜测：未被打标的日期一律按工作日处理，
     * 由开放日历单日覆盖。
     *
     * @param date 日期
     * @return 日型
     */
    public static DayType ofDate(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? WEEKEND : WORKDAY;
    }
}

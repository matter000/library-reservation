package com.libseat.enums;

import lombok.Getter;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 日型（开放档案的分组维度）。
 *
 * <p>只区分工作日 / 周末 / 节假日 / 考试周四类；具体某天的例外由
 * {@code t_open_calendar} 覆盖，不在此枚举里表达。
 */
@Getter
public enum DayType {

    /** 工作日。 */
    WORKDAY("WORKDAY", "工作日"),

    /** 周末。 */
    WEEKEND("WEEKEND", "周末"),

    /** 节假日。 */
    HOLIDAY("HOLIDAY", "节假日"),

    /**
     * 考试周（PRD §12-1、§12-3）。
     *
     * <p>★ 它不是"另一种周末"：考试周改的是**规则配额**（单日累计 6 小时放宽到 8 小时），
     *   而窗口时刻是否延长取决于开放档案 —— 两者分开配置，
     *   因为 PRD 想要的"考试周开到 23:00"需要 30 分钟网格锚点前移（尚未做，见 DESIGN §14.2），
     *   而 8 小时单日累计现在就能生效。把日型加进枚举是为了让后者有承载点，
     *   而不是让"考试周"退化成管理员手写的一条备注。
     */
    EXAM("EXAM", "考试周");

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

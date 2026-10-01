package com.libseat.repository;

import com.libseat.entity.OpenCalendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 开放日历数据访问层。
 */
@Repository
public interface OpenCalendarRepository extends JpaRepository<OpenCalendar, Long> {

    /**
     * 取某天的覆盖项。
     *
     * @param date 日期
     * @return 覆盖项
     */
    Optional<OpenCalendar> findByOpenDate(LocalDate date);

    /**
     * 取日期区间内的覆盖项（管理面列表用）。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 覆盖项列表
     */
    List<OpenCalendar> findByOpenDateBetweenOrderByOpenDateAsc(LocalDate from, LocalDate to);

    /**
     * 判断某天是否已被标记闭馆。
     *
     * @param date 日期
     * @return 是否闭馆
     */
    default boolean isClosed(LocalDate date) {
        return findByOpenDate(date).map(c -> Boolean.TRUE.equals(c.getClosed())).orElse(false);
    }
}

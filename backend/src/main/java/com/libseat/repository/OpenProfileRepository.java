package com.libseat.repository;

import com.libseat.entity.OpenProfile;
import com.libseat.enums.DayType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 开放档案数据访问层。
 */
@Repository
public interface OpenProfileRepository extends JpaRepository<OpenProfile, Long> {

    /**
     * 按日型取启用的档案。
     *
     * @param dayType 日型
     * @return 档案
     */
    Optional<OpenProfile> findFirstByDayTypeAndEnabledTrue(DayType dayType);

    /**
     * 按日型取档案（不区分是否启用，管理面唯一性判断用）。
     *
     * @param dayType 日型
     * @return 档案
     */
    Optional<OpenProfile> findByDayType(DayType dayType);

    /**
     * 取全部启用档案。
     *
     * @return 档案列表
     */
    List<OpenProfile> findByEnabledTrueOrderByDayTypeAsc();
}

package com.libseat.repository;

import com.libseat.entity.PeakWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 高峰窗口数据访问层。
 */
@Repository
public interface PeakWindowRepository extends JpaRepository<PeakWindow, Long> {

    /**
     * 取全部启用的高峰窗口。
     *
     * @return 窗口列表
     */
    List<PeakWindow> findByEnabledTrue();

    /**
     * 取作用域命中某楼层或某分区的启用窗口。
     *
     * <p>GLOBAL 作用域对任意楼层/分区都生效；FLOOR 只看同层；ZONE 只看同区。
     *
     * @param floorId 楼层 ID
     * @param zoneId  分区 ID
     * @return 窗口列表
     */
    @Query("select p from PeakWindow p where p.enabled = true and ("
            + " p.scope = 'GLOBAL'"
            + " or (p.scope = 'FLOOR' and p.floorId = :floorId)"
            + " or (p.scope = 'ZONE' and p.zoneId = :zoneId))")
    List<PeakWindow> findApplicable(@Param("floorId") Long floorId, @Param("zoneId") Long zoneId);
}

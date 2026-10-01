package com.libseat.repository;

import com.libseat.entity.SeatTable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 桌位数据访问层。
 */
@Repository
public interface SeatTableRepository extends JpaRepository<SeatTable, Long> {

    /**
     * 查询某分区全部桌位。
     *
     * @param zoneId 分区 ID
     * @return 桌位列表
     */
    List<SeatTable> findByZoneIdOrderByTableNoAsc(Long zoneId);

    /**
     * 查询某楼层全部桌位。
     *
     * @param floorId 楼层 ID
     * @return 桌位列表
     */
    List<SeatTable> findByFloorId(Long floorId);

    /**
     * 按「分区 + 桌号」定位桌位（uk_table_zone_no）。
     *
     * @param zoneId  分区 ID
     * @param tableNo 桌号
     * @return 桌位
     */
    Optional<SeatTable> findByZoneIdAndTableNo(Long zoneId, String tableNo);

    /**
     * 判断某分区下桌号是否已存在。
     *
     * @param zoneId  分区 ID
     * @param tableNo 桌号
     * @return 是否存在
     */
    boolean existsByZoneIdAndTableNo(Long zoneId, String tableNo);
}

package com.libseat.repository;

import com.libseat.entity.Zone;
import com.libseat.enums.FloorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 分区数据访问层。
 */
@Repository
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    /**
     * 查询某楼层全部分区（按排序号，管理面与学生面共用）。
     *
     * @param floorId 楼层 ID
     * @return 分区列表
     */
    List<Zone> findByFloorIdOrderBySortOrderAscIdAsc(Long floorId);

    /**
     * 查询某楼层下指定开放状态的分区。
     *
     * @param floorId 楼层 ID
     * @param status  开放状态
     * @return 分区列表
     */
    List<Zone> findByFloorIdAndStatusOrderBySortOrderAscIdAsc(Long floorId, FloorStatus status);

    /**
     * 查询若干楼层的分区。
     *
     * @param floorIds 楼层 ID 集合
     * @return 分区列表
     */
    List<Zone> findByFloorIdInOrderByFloorIdAscSortOrderAscIdAsc(List<Long> floorIds);

    /**
     * 按「楼层 + 编码」定位分区（uk_zone_floor_code）。
     *
     * @param floorId 楼层 ID
     * @param code    分区编码
     * @return 分区
     */
    Optional<Zone> findByFloorIdAndCode(Long floorId, String code);

    /**
     * 判断某楼层下分区编码是否已存在。
     *
     * @param floorId 楼层 ID
     * @param code    分区编码
     * @return 是否存在
     */
    boolean existsByFloorIdAndCode(Long floorId, String code);

    /**
     * 统计某楼层的分区数。
     *
     * @param floorId 楼层 ID
     * @return 分区数量
     */
    long countByFloorId(Long floorId);
}

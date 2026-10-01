package com.libseat.repository;

import com.libseat.entity.Seat;
import com.libseat.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 座位数据访问层。
 */
@Repository
public interface SeatRepository extends JpaRepository<Seat, Long>, JpaSpecificationExecutor<Seat> {

    /**
     * 查询某楼层全部座位（按行列排序，平面图/管理列表共用）。
     *
     * @param floorId 楼层 ID
     * @return 座位列表
     */
    List<Seat> findByFloorIdOrderByRowNoAscColNoAsc(Long floorId);

    /**
     * 判断某楼层下座位编号是否已存在（批量生成去重）。
     *
     * @param floorId 楼层 ID
     * @param seatNo  座位编号
     * @return 是否存在
     */
    boolean existsByFloorIdAndSeatNo(Long floorId, String seatNo);

    /**
     * 统计某楼层座位数。
     *
     * @param floorId 楼层 ID
     * @return 座位数量
     */
    long countByFloorId(Long floorId);

    /**
     * 统计某状态的座位数。
     *
     * @param status 座位状态
     * @return 座位数量
     */
    long countByStatus(SeatStatus status);

    /**
     * 按座位编号模糊查询（管理员预约记录关键字搜索）。
     *
     * @param seatNo 座位编号关键字
     * @return 座位列表
     */
    List<Seat> findBySeatNoContaining(String seatNo);

    /**
     * 查询某楼层下处于可用状态的座位。
     *
     * @param floorId 楼层 ID
     * @param status  座位状态
     * @return 座位列表
     */
    List<Seat> findByFloorIdAndStatus(Long floorId, SeatStatus status);

    /**
     * 统计某分区的座位数。
     *
     * @param zoneId 分区 ID
     * @return 座位数量
     */
    long countByZoneId(Long zoneId);

    /**
     * 统计某桌位的座位数（与 t_table.seat_count 声明值对账）。
     *
     * @param tableId 桌位 ID
     * @return 座位数量
     */
    long countByTableId(Long tableId);

    /**
     * 查询某桌位的全部座位。
     *
     * @param tableId 桌位 ID
     * @return 座位列表
     */
    List<Seat> findByTableId(Long tableId);

    /**
     * 查询某分区全部座位。
     *
     * @param zoneId 分区 ID
     * @return 座位列表
     */
    List<Seat> findByZoneId(Long zoneId);

    /**
     * 查询某楼层全部座位（分区回填与按分区聚合用）。
     *
     * @param floorId 楼层 ID
     * @return 座位列表
     */
    List<Seat> findByFloorId(Long floorId);

    /**
     * 统计尚未归属分区的座位数（「无孤儿」断言用）。
     *
     * @return zone_id 为空的座位数量
     */
    long countByZoneIdIsNull();
}

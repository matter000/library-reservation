package com.libseat.repository;

import com.libseat.entity.Floor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 楼层数据访问层。
 */
@Repository
public interface FloorRepository extends JpaRepository<Floor, Long> {

    /**
     * 按排序号升序返回全部楼层。
     *
     * @return 楼层列表
     */
    List<Floor> findAllByOrderBySortOrderAscIdAsc();

    /**
     * 按编号查询。
     *
     * @param code 楼层编号
     * @return 楼层
     */
    Optional<Floor> findByCode(String code);

    /**
     * 判断编号是否已存在。
     *
     * @param code 楼层编号
     * @return 是否存在
     */
    boolean existsByCode(String code);
}

package com.libseat.repository;

import com.libseat.entity.LeaveReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 暂离原因字典数据访问层。
 */
@Repository
public interface LeaveReasonRepository extends JpaRepository<LeaveReason, String> {

    /**
     * 已启用的原因，按展示顺序。
     *
     * @return 原因列表
     */
    List<LeaveReason> findByEnabledTrueOrderBySortOrderAscCodeAsc();
}

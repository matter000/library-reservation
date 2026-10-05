package com.libseat.repository;

import com.libseat.entity.PatrolTask;
import com.libseat.enums.PatrolStatus;
import com.libseat.enums.PatrolType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 巡查任务数据访问层。
 *
 * <p>★ 只用等值条件的派生查询，不写 {@code where (:x is null or ...)} 这类可选参数 ——
 * H2 与 MySQL 对 {@code NULL} 参数的比较结果不同（S3 申诉队列、S5 候补队列都为此改过一次）。
 * 看板的楼层 / 等级筛选在内存里做：本表的「未处理」行按唯一索引最多每座一条，量级是座位数而不是流水数。
 */
@Repository
public interface PatrolTaskRepository extends JpaRepository<PatrolTask, Long> {

    /** 未处理名额占用的 flag 值（终态为 null）。 */
    Integer OPEN_FLAG = 1;

    /**
     * 某座位当前未处理的那条任务（重复扫描的幂等判定）。
     *
     * @param seatId   座位
     * @param openFlag 传 {@link #OPEN_FLAG}
     * @return 任务，无为 empty
     */
    Optional<PatrolTask> findBySeatIdAndOpenFlag(Long seatId, Integer openFlag);

    /**
     * 全部未处理任务（看板默认视图）。
     *
     * @param openFlag 传 {@link #OPEN_FLAG}
     * @return 按生成先后
     */
    List<PatrolTask> findByOpenFlagOrderByIdAsc(Integer openFlag);

    /**
     * 某座位近期是否存在同类型的「已提醒」处置（等级升级依据）。
     *
     * @param seatId 座位
     * @param type   疑点类型
     * @param status {@code REMIND}
     * @param after  时刻下界
     * @return 是否存在
     */
    boolean existsBySeatIdAndTypeAndStatusAndHandleTimeAfter(Long seatId, PatrolType type,
                                                            PatrolStatus status, LocalDateTime after);
}

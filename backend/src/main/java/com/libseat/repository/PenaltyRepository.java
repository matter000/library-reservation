package com.libseat.repository;

import com.libseat.entity.Penalty;
import com.libseat.enums.PenaltyStatus;
import com.libseat.enums.PenaltyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 处罚数据访问层。
 */
@Repository
public interface PenaltyRepository extends JpaRepository<Penalty, Long> {

    /**
     * 某人当前 ACTIVE 的处罚（判活用；是否真过期由 {@code activeAt} 再确认一次）。
     *
     * @param userId 用户 ID
     * @param status 传 {@link PenaltyStatus#ACTIVE}
     * @return 处罚列表
     */
    List<Penalty> findByUserIdAndStatusOrderByIdDesc(Long userId, PenaltyStatus status);

    /**
     * 同类处罚的唯一 ACTIVE 行（幂等键：延长它而不是再插一条）。
     *
     * @param userId 用户 ID
     * @param type   处罚类型
     * @param status 传 {@link PenaltyStatus#ACTIVE}
     * @return 已有处罚
     */
    Optional<Penalty> findFirstByUserIdAndTypeAndStatus(Long userId, PenaltyType type, PenaltyStatus status);

    /**
     * 全部处于 ACTIVE 的处罚行（监控大屏与信用导出用，T-V2-22）。
     *
     * <p>★ 这里只按状态粗筛，是否真生效仍要交给 {@code Penalty#activeAt(now)}：
     * 「墙钟到了却没被扫描置回」这段时间里，行仍是 ACTIVE 却已过期，
     * 判活口径必须只有一个出处（S5 的抖动坑就是在这里修掉的）。
     *
     * @param status 传 {@link PenaltyStatus#ACTIVE}
     * @return 处罚列表
     */
    List<Penalty> findByStatusOrderByIdAsc(PenaltyStatus status);
}

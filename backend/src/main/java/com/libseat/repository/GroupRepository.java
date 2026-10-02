package com.libseat.repository;

import com.libseat.entity.Group;
import com.libseat.enums.GroupStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 队伍数据访问。
 *
 * <p>到点要锁定的队伍用「状态 + 锁定时刻」联合索引扫（{@code idx_group_status}），
 * 且只扫未锁定的：{@code FORMING} 之外都不该再被锁定任务碰到。
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {

    /**
     * 某人的队伍（组长视角）。
     *
     * @param leaderId 组长 ID
     * @param statuses 状态集合
     * @return 队伍列表（新的在前）
     */
    List<Group> findByLeaderIdAndStatusInOrderByIdDesc(Long leaderId, List<GroupStatus> statuses);

    /**
     * 邀请码定位队伍（不区分大小写由服务层归一化为大写）。
     *
     * @param code 邀请码
     * @return 队伍
     */
    Optional<Group> findByInviteCode(String code);

    /**
     * 到点待锁定的队伍。
     *
     * @param status 期望的当前状态（一般传 {@code FORMING}）
     * @param now    当前时刻
     * @return 队伍列表
     */
    @Query("select g from Group g where g.status = :status and g.lockTime <= :now")
    List<Group> findDueForLock(@Param("status") GroupStatus status, @Param("now") LocalDateTime now);

    /**
     * 处于某状态的队伍（缺席扫描等后续任务用）。
     *
     * @param status 状态
     * @return 队伍列表
     */
    List<Group> findByStatus(GroupStatus status);
}

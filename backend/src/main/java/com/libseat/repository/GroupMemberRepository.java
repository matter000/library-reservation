package com.libseat.repository;

import com.libseat.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 队伍成员数据访问。
 */
@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

    /**
     * 某队全员（按加入顺序）。
     *
     * @param groupId 队伍 ID
     * @return 成员列表
     */
    List<GroupMember> findByGroupIdOrderByIdAsc(Long groupId);

    /**
     * 某人在某队的身份。
     *
     * @param groupId 队伍 ID
     * @param userId  用户 ID
     * @return 成员记录
     */
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    /**
     * 某人参与中的成员记录（学生面「我的队伍」入口用）。
     *
     * @param userId 用户 ID
     * @return 成员列表（新的在前）
     */
    List<GroupMember> findByUserIdOrderByIdDesc(Long userId);

    /**
     * 某队已确认的人数（成团判定与锁定时校验用）。
     *
     * @param groupId 队伍 ID
     * @return 人数
     */
    long countByGroupIdAndInviteStatus(Long groupId, com.libseat.enums.InviteStatus inviteStatus);
}

package com.libseat.repository;

import com.libseat.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户数据访问层。
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * 按账号查询。
     *
     * @param username 账号
     * @return 用户
     */
    Optional<User> findByUsername(String username);

    /**
     * 判断账号是否已存在。
     *
     * @param username 账号
     * @return 是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 关键字模糊匹配账号或姓名（管理员预约记录搜索用）。
     *
     * @param username 账号关键字
     * @param realName 姓名关键字
     * @return 用户列表
     */
    List<User> findByUsernameContainingOrRealNameContaining(String username, String realName);
}

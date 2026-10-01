package com.libseat.repository;

import com.libseat.entity.RuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 规则配置数据访问。
 */
public interface RuleConfigRepository extends JpaRepository<RuleConfig, Long> {

    /**
     * 按作用域与键查询。
     *
     * @param scope   作用域（GLOBAL/ZONE/DAY_TYPE）
     * @param scopeId 作用域对象 ID（GLOBAL 为空串）
     * @param rkey    规则键
     * @return 配置项
     */
    Optional<RuleConfig> findByScopeAndScopeIdAndRkey(String scope, String scopeId, String rkey);

    List<RuleConfig> findAllByOrderByScopeAscScopeIdAscRkeyAsc();

    List<RuleConfig> findByRgroupOrderByScopeAscScopeIdAscRkeyAsc(String rgroup);
}

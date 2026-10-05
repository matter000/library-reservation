package com.libseat.repository;

import com.libseat.entity.Notice;
import com.libseat.enums.NoticeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 站内通知数据访问层。
 *
 * <p>★ 一律等值派生查询：可选参数式的 {@code where (:x is null or ...)} 在 H2 与 MySQL 上
 * 对 {@code NULL} 的比较结果不同，S3 的申诉队列已经为此改过一次。
 */
@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /**
     * 某人最近的通知（新的在前）。
     *
     * @param userId   收件人
     * @param pageable 取前 N 条（铃铛只翻最近一屏）
     * @return 列表
     */
    List<Notice> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /**
     * 某人某条（按 ID + 人，跨人读取天然查不到）。
     *
     * @param id     通知 ID
     * @param userId 收件人
     * @return 通知，无为 empty
     */
    Optional<Notice> findByIdAndUserId(Long id, Long userId);

    /**
     * 某人未读数。
     *
     * @param userId   收件人
     * @param readFlag 传 {@code false}
     * @return 条数
     */
    long countByUserIdAndReadFlag(Long userId, Boolean readFlag);

    /**
     * 某人通知总数（列表只回最近一屏，前端要能说明「还有更早的」）。
     *
     * @param userId 收件人
     * @return 条数
     */
    long countByUserId(Long userId);

    /**
     * 去重判定：这件事是否已经打扰过。
     *
     * @param userId 收件人
     * @param type   类型
     * @param refType 业务对象类型
     * @param refId  业务记录 ID
     * @return 是否已存在
     */
    boolean existsByUserIdAndTypeAndRefTypeAndRefId(Long userId, NoticeType type, String refType, Long refId);
}

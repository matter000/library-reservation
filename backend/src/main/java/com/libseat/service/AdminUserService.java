package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.PageResult;
import com.libseat.dto.AdminDTO;
import com.libseat.entity.User;
import com.libseat.enums.UserRole;
import com.libseat.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员·用户列表（P1 可降级）。手机号必须脱敏：{@code 138****1234}。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    /**
     * 用户分页列表。
     *
     * @param keyword 关键字（账号 / 姓名）
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<AdminDTO.UserVO> page(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? 20 : size;
        String trimmedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;

        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (trimmedKeyword != null) {
                String like = "%" + trimmedKeyword + "%";
                predicates.add(cb.or(
                        cb.like(root.get("username"), like),
                        cb.like(root.get("realName"), like)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<User> userPage = userRepository.findAll(
                spec, PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "id")));

        List<AdminDTO.UserVO> list = new ArrayList<>(userPage.getContent().size());
        for (User user : userPage.getContent()) {
            list.add(toVO(user));
        }
        return PageResult.<AdminDTO.UserVO>builder()
                .list(list)
                .total(userPage.getTotalElements())
                .page(safePage)
                .size(safeSize)
                .build();
    }

    private AdminDTO.UserVO toVO(User user) {
        UserRole role = user.getRole() == null ? UserRole.STUDENT : user.getRole();
        return AdminDTO.UserVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .phoneMasked(BizConstants.maskPhone(user.getPhone()))
                .role(role.name())
                .roleText(role.getText())
                .noShowCount(user.getNoShowCount() == null ? 0 : user.getNoShowCount())
                .status(user.getStatus())
                .createTime(BizConstants.formatDateTime(user.getCreateTime()))
                .build();
    }
}

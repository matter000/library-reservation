package com.libseat.service;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.dto.AuthDTO;
import com.libseat.entity.User;
import com.libseat.enums.UserRole;
import com.libseat.repository.UserRepository;
import com.libseat.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证服务：注册 / 登录 / 获取当前用户。
 *
 * <p>注册接口后端强制 role=USER，不接受任何外来角色字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String ACCOUNT_STATUS_ACTIVE = "ACTIVE";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtUtil jwtUtil;

    private final AuditService auditService;

    /**
     * 用户注册（★ 强制 role=USER）。
     *
     * @param request 注册请求
     * @return 用户信息
     */
    @Transactional
    public AuthDTO.UserInfo register(AuthDTO.RegisterRequest request) {
        String username = request.getUsername().trim();
        String realName = request.getRealName().trim();
        String password = request.getPassword();

        if (!password.equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "两次输入的密码不一致");
        }
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该账号已被注册");
        }

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .realName(realName)
                .phone(StringUtils.hasText(request.getPhone()) ? request.getPhone().trim() : null)
                .role(UserRole.STUDENT)
                .noShowCount(0)
                .status(ACCOUNT_STATUS_ACTIVE)
                .build();
        User saved = userRepository.save(user);
        log.info("新用户注册成功：username={}, userId={}", saved.getUsername(), saved.getId());
        return toUserInfo(saved);
    }

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return 登录响应（Token + 用户信息）
     */
    @Transactional(readOnly = true)
    public AuthDTO.LoginResponse login(AuthDTO.LoginRequest request) {
        String username = request.getUsername().trim();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "用户名或密码错误"));
        if (!ACCOUNT_STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被停用，请联系管理员");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户名或密码错误");
        }
        String token = jwtUtil.generate(user);
        return AuthDTO.LoginResponse.builder()
                .token(token)
                .tokenType(JwtUtil.TOKEN_TYPE)
                .expiresIn(jwtUtil.expiresInSeconds())
                .user(toUserInfo(user))
                .build();
    }

    /**
     * 获取当前登录用户。
     *
     * @param userId 用户 ID
     * @return 用户信息
     */
    @Transactional(readOnly = true)
    public AuthDTO.UserInfo me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        return toUserInfo(user);
    }

    /**
     * 修改当前登录用户的密码。
     *
     * <p>校验顺序：原密码正确 → 两次新密码一致 → 新密码不得与原密码相同。
     *
     * <p>★ 已知限制：JWT 是无状态的，改密不会让已签发的旧 Token 立即失效
     * （需要 Token 版本号或黑名单才能做到，属架构级改动，本轮不动）。
     * 前端在改密成功后会主动清除本地登录态并跳转登录页作为补偿。
     *
     * @param userId  当前登录用户 ID（取自 UserContext，不接受前端传入）
     * @param request 修改密码请求
     */
    @Transactional
    public void changePassword(Long userId, AuthDTO.ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "原密码不正确");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "两次输入的新密码不一致");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "新密码不能与原密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        auditService.record(AuditService.ACTION_PASSWORD_CHANGE, "USER", userId, null);
        log.info("用户修改密码成功：userId={}", userId);
    }

    private AuthDTO.UserInfo toUserInfo(User user) {
        return AuthDTO.UserInfo.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .role(user.getRole() == null ? UserRole.STUDENT.name() : user.getRole().name())
                .phoneMasked(BizConstants.maskPhone(user.getPhone()))
                .noShowCount(user.getNoShowCount() == null ? 0 : user.getNoShowCount())
                .build();
    }
}

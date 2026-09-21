package com.libseat.security;

import com.libseat.common.BusinessException;
import com.libseat.common.ErrorCode;
import com.libseat.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具：生成 / 解析 Token。
 *
 * <p>Payload：{@code {sub: userId, username, role, realName, iat, exp}}，HS256 签名。
 */
@Component
public class JwtUtil {

    /** Token 类型响应字段值。 */
    public static final String TOKEN_TYPE = "Bearer";

    private final String secret;

    private final long expireHours;

    /**
     * 构造注入配置。
     *
     * @param secret      签名密钥
     * @param expireHours 有效期（小时）
     */
    public JwtUtil(@Value("${app.jwt.secret}") String secret,
                   @Value("${app.jwt.expire-hours}") long expireHours) {
        this.secret = secret;
        this.expireHours = expireHours;
    }

    /**
     * 生成 Token。
     *
     * @param user 用户实体
     * @return JWT 字符串
     */
    public String generate(User user) {
        Map<String, Object> claims = new HashMap<>(4);
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole() == null ? "USER" : user.getRole().name());
        claims.put("realName", user.getRealName());
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(String.valueOf(user.getId()))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expireHours * 3600_000L))
                .signWith(signKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Token；失败一律抛 4010。
     *
     * @param token JWT 字符串
     * @return Claims
     */
    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * Token 有效期（秒）。
     *
     * @return 有效期秒数
     */
    public long expiresInSeconds() {
        return expireHours * 3600L;
    }

    private Key signKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}

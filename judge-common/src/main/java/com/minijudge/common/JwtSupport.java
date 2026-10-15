package com.minijudge.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发/解析的无状态工具（user-service 签发、gateway 校验，同一密钥配置项）。
 *
 * <p>微服务鉴权模式（计划书第 4 章）：网关验证令牌后向下游注入 X-User-Id 头，
 * 各服务信任"来自网关的内网流量"——所以只有网关暴露端口，服务不直接对外。</p>
 */
public final class JwtSupport {

    private JwtSupport() {}

    /**
     * 令牌载荷结构：subject=userId，username/role 自定义 claim。
     *
     * <p>RBAC 收口说明：role claim 让令牌"自描述"角色，仅供网关透传展示与
     * 前端即时渲染用——管理接口的授权判定绝不依赖令牌里的这个值，
     * 而是每次现查 user-service/数据库（角色变更即时生效，不用等令牌过期重签）。</p>
     */
    public record TokenUser(Long userId, String username, String role) {

        /** 向后兼容收口：旧令牌没有 role claim（或值为空）时，按最小权限原则视为 USER */
        public String roleOrUser() {
            return (role == null || role.isBlank()) ? "USER" : role;
        }
    }

    /**
     * 签发令牌。role 允许传 null（例如历史调用方/测试数据没有角色列），
     * 此时不写 role claim，解析端按 USER 兜底——与旧版令牌行为完全一致。
     */
    public static String generate(String secret, long expireMinutes, Long userId,
                                  String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        var builder = Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMinutes * 60_000L));
        if (role != null && !role.isBlank()) {
            builder.claim("role", role);
        }
        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    /** 解析失败（签名错/过期/格式坏）返回 null，由调用方决定拒绝策略 */
    public static TokenUser parse(String secret, String token) {
        try {
            Claims c = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            // 旧令牌（改动前签发）没有 role claim：get 返回 null，由 roleOrUser() 兜底成 USER
            return new TokenUser(Long.valueOf(c.getSubject()),
                    c.get("username", String.class),
                    c.get("role", String.class));
        } catch (Exception e) {
            return null;
        }
    }
}

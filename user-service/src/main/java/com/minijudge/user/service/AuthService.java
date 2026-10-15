package com.minijudge.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.minijudge.common.JwtSupport;
import com.minijudge.common.Result;
import com.minijudge.user.entity.User;
import com.minijudge.user.mapper.UserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 注册/登录：与单体版同一套安全逻辑（唯一键防竞态、统一错误信息防用户枚举），
 * 差异只在不走 Spring Security、直接返回 Result（网关统一收口，错误码即可）。
 */
@Service
@RequiredArgsConstructor   // 为 final 的 userMapper 生成构造器，Spring 按构造注入
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${mini-judge.jwt.secret}")
    private String jwtSecret;

    /**
     * 管理引导密钥（RBAC 收口的演示链路保障）：注册请求带对的 key 则账号 role=ADMIN；
     * 用户名已存在且带对 key 时，幂等地把已有账号提升为 ADMIN（保住前端
     * "一键初始化演示题"在存量库上的可用性）。
     *
     * <p>安全边界：key 本身就是"授予管理员"的凭证，能拿到 key 即能引导 ADMIN，
     * 所以生产环境必须把该配置挪进配置中心（Nacos Config）并加密/轮换，
     * 前端产物里绝不能打包真实 key——现在这份 yml 默认值只适用于本机演示 profile。
     * 留空（未配置）= 引导通道关闭，注册一律 USER，是最安全的缺省。</p>
     */
    @Value("${mini-judge.admin-bootstrap-key:}")
    private String adminBootstrapKey;

    @Data
    public static class AccountReq {
        /** 注册/登录共用请求体；password 至少 6 位由 Controller @Valid 兜底 */
        private String username;
        private String password;
        private String nickname;
        /** 仅注册时生效的管理引导密钥（见 adminBootstrapKey 字段注释），登录忽略该字段 */
        private String adminBootstrapKey;
    }

    /** 请求密钥与服务端配置密钥是否匹配（配置留空=引导关闭；trim 防手滑空格） */
    private boolean bootstrapKeyMatches(String reqKey) {
        return adminBootstrapKey != null && !adminBootstrapKey.isBlank()
                && reqKey != null && adminBootstrapKey.equals(reqKey.trim());
    }

    public Result<Void> register(AccountReq req) {
        boolean admin = bootstrapKeyMatches(req.getAdminBootstrapKey());
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPasswordHash(encoder.encode(req.getPassword()));
        u.setNickname(req.getNickname() == null ? req.getUsername() : req.getNickname());
        // 普通注册恒 USER；只有带对引导 key 才 ADMIN（服务端强校验，不信任前端自报角色）
        u.setRole(admin ? "ADMIN" : "USER");
        try {
            userMapper.insert(u);
        } catch (DuplicateKeyException e) {
            // 幂等引导：用户名已存在 + 引导 key 正确 → 把既有账号提升为 ADMIN。
            // 让"重复初始化"与"首次初始化"殊途同归（前端 demo 链路因此不会被 RBAC 打破）。
            // 提升条件只验 key 不验密码——key 即授权凭证，见 adminBootstrapKey 字段的安全边界注释。
            if (admin) {
                userMapper.update(null,
                        Wrappers.<User>lambdaUpdate()
                                .set(User::getRole, "ADMIN")
                                .eq(User::getUsername, req.getUsername()));
            }
            return Result.fail(40901, "用户名已被占用");
        }
        return Result.ok(null);
    }

    public Result<Map<String, Object>> login(AccountReq req) {
        // 注意：实体 passwordHash 标了 @TableField(select=false)（默认查询不带敏感列），
        // 登录恰恰需要这一列——必须显式 select() 点名要它，否则取出恒为 null，
        // BCrypt matches 恒 false，表现为"注册成功但永远登录失败"。
        User u = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getUsername, User::getNickname,
                                User::getRole, User::getPasswordHash)
                        .eq(User::getUsername, req.getUsername()));
        // u==null 也要走完 matches（固定耗时分支更好写法是 dummy compare；MVP 先注明风险）
        if (u == null || !encoder.matches(req.getPassword(), u.getPasswordHash())) {
            return Result.fail(40101, "用户名或密码错误");
        }
        // RBAC 收口：role 一并写进 JWT claim（自描述，供展示/网关透传；
        // 管理接口的授权判定仍走服务端现查——令牌里的角色不作数）。
        // 无角色数据（历史行 role 为 NULL）按最小权限兜底成 USER，
        // 顺带修掉原 Map.of 在 role=null 时抛 NPE 的隐患。
        String role = (u.getRole() == null || u.getRole().isBlank()) ? "USER" : u.getRole();
        String token = JwtSupport.generate(jwtSecret, 720, u.getId(), u.getUsername(), role);
        return Result.ok(Map.of(
                "token", token,
                "userId", u.getId(),
                "nickname", u.getNickname(),
                "role", role));
    }

    /**
     * 内部角色查询（GET /api/users/{id}/role）：供 judge-service 做 rejudge
     * 服务端强校验用。角色现查数据库，令牌/缓存里的都不可信；
     * 查无此人返回 40400，由调用方按"非 ADMIN 拒绝"处理（fail-closed）。
     */
    public Result<String> roleOf(Long userId) {
        User u = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getRole)
                        .eq(User::getId, userId));
        if (u == null) {
            return Result.fail(40400, "用户不存在: " + userId);
        }
        // 无角色数据视为 USER（向后兼容 + 最小权限，与 JwtSupport 令牌兜底同一规则）
        String role = (u.getRole() == null || u.getRole().isBlank()) ? "USER" : u.getRole();
        return Result.ok(role);
    }
}

package com.minijudge.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.minijudge.common.JwtSupport;
import com.minijudge.common.Result;
import com.minijudge.user.entity.User;
import com.minijudge.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RBAC 收口的 user-service 侧回归防线：
 * 1. 注册默认 USER，只有带对引导 key 才 ADMIN（服务端判定，不信任前端自报角色）；
 * 2. 用户名已存在 + 带对 key → 幂等提升为 ADMIN（保住前端 demo 初始化链路）；
 * 3. 登录令牌的 role claim 与库内角色一致；库内无角色数据（NULL）按 USER 兜底
 *    ——同一条兜底规则也覆盖"旧令牌无 role claim 视为 USER"的向后兼容语义。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "mini-judge-dev-secret-key-must-be-at-least-32-bytes!";
    private static final String BOOTSTRAP_KEY = "test-admin-key-2026";

    @Mock
    private UserMapper userMapper;

    private AuthService service;

    /**
     * 纯 Mockito 环境没有 MyBatis 启动流程，lambda 条件构造器
     * （Wrappers.lambdaQuery().select / lambdaUpdate().set）要的实体列缓存不会自动装填——
     * 手工 initTableInfo 一次，等价于运行期扫描实体的那步（不为此拉起整个 Spring 上下文）。
     */
    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        service = new AuthService(userMapper);
        ReflectionTestUtils.setField(service, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(service, "adminBootstrapKey", BOOTSTRAP_KEY);
    }

    private AuthService.AccountReq req(String username, String password, String key) {
        AuthService.AccountReq r = new AuthService.AccountReq();
        r.setUsername(username);
        r.setPassword(password);
        r.setAdminBootstrapKey(key);
        return r;
    }

    // ── 注册：引导 key 决定角色 ──────────────────────────────────────────

    @Test
    @DisplayName("普通注册（不带 key）恒为 USER")
    void registerWithoutKeyIsUser() {
        Result<Void> res = service.register(req("alice", "pass1234", null));
        assertEquals(0, res.code);
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        assertEquals("USER", cap.getValue().getRole());
    }

    @Test
    @DisplayName("带对引导 key 注册 → ADMIN（服务端强校验 key，非前端自报角色）")
    void registerWithCorrectKeyIsAdmin() {
        service.register(req("demo", "demo123456", BOOTSTRAP_KEY));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        assertEquals("ADMIN", cap.getValue().getRole());
    }

    @Test
    @DisplayName("带错引导 key 注册 → USER，且不给任何提升提示")
    void registerWithWrongKeyIsUser() {
        service.register(req("mallory", "pass1234", "guessed-key"));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        assertEquals("USER", cap.getValue().getRole());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("服务端未配置引导 key（留空）→ 引导通道关闭，带什么都只是 USER")
    void bootstrapChannelClosedWhenKeyNotConfigured() {
        ReflectionTestUtils.setField(service, "adminBootstrapKey", "");
        service.register(req("demo", "demo123456", ""));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(cap.capture());
        assertEquals("USER", cap.getValue().getRole());
    }

    // ── 注册撞名：幂等提升保住 demo 链路 ────────────────────────────────

    @Test
    @DisplayName("用户名已存在 + 带对 key → 幂等提升已有账号为 ADMIN，仍回 40901")
    void duplicateUserWithKeyIsPromoted() {
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("uk_username"));
        Result<Void> res = service.register(req("demo", "whatever", BOOTSTRAP_KEY));
        assertEquals(40901, res.code);
        verify(userMapper).update(any(), any());   // lambdaUpdate set role=ADMIN 走了更新
    }

    @Test
    @DisplayName("用户名已存在 + 无 key → 原样 40901，绝不动角色（防撞名提权）")
    void duplicateUserWithoutKeyNotTouched() {
        when(userMapper.insert(any(User.class))).thenThrow(new DuplicateKeyException("uk_username"));
        Result<Void> res = service.register(req("alice", "whatever", null));
        assertEquals(40901, res.code);
        verify(userMapper, never()).update(any(), any());
    }

    // ── 登录：role claim 进令牌 + 无角色数据兜底 USER ────────────────────

    private User dbUser(String role) {
        User u = new User();
        u.setId(10L);
        u.setUsername("demo");
        u.setNickname("演示账号");
        u.setRole(role);
        // 与 service 内部同一 BCryptPasswordEncoder：先 encode 再让 matches 校验
        u.setPasswordHash(new org.springframework.security.crypto.bcrypt
                .BCryptPasswordEncoder().encode("demo123456"));
        return u;
    }

    @Test
    @DisplayName("登录成功：响应 role 与令牌 role claim 都取库内实况")
    void loginTokenCarriesRoleClaim() {
        when(userMapper.selectOne(any())).thenReturn(dbUser("ADMIN"));
        Result<Map<String, Object>> res = service.login(req("demo", "demo123456", null));
        assertEquals(0, res.code);
        assertEquals("ADMIN", res.data.get("role"));
        JwtSupport.TokenUser parsed = JwtSupport.parse(SECRET, (String) res.data.get("token"));
        assertEquals("ADMIN", parsed.role());
        assertEquals(10L, parsed.userId());
    }

    @Test
    @DisplayName("库内 role 为 NULL（历史脏数据）→ 登录响应与令牌都兜底 USER，不再 NPE")
    void loginNullRoleDefaultsToUser() {
        when(userMapper.selectOne(any())).thenReturn(dbUser(null));
        Result<Map<String, Object>> res = service.login(req("demo", "demo123456", null));
        assertEquals(0, res.code);
        assertEquals("USER", res.data.get("role"));
        JwtSupport.TokenUser parsed = JwtSupport.parse(SECRET, (String) res.data.get("token"));
        assertEquals("USER", parsed.roleOrUser());
    }

    @Test
    @DisplayName("旧令牌（无 role claim）解析后 roleOrUser 兜底 USER（向后兼容收口）")
    void legacyTokenWithoutRoleClaimParsesAsUser() {
        // 手工构造"改动前形态"的令牌：generate 传 null role 即不写 claim
        String legacy = JwtSupport.generate(SECRET, 60, 7L, "old", null);
        JwtSupport.TokenUser parsed = JwtSupport.parse(SECRET, legacy);
        assertNull(parsed.role());
        assertEquals("USER", parsed.roleOrUser());
        assertEquals(7L, parsed.userId());
    }

    // ── 内部角色查询接口（judge-service rejudge 依赖）────────────────────

    @Test
    @DisplayName("roleOf：查到 ADMIN 原样返回")
    void roleOfReturnsAdmin() {
        User u = new User();
        u.setId(10L);
        u.setRole("ADMIN");
        when(userMapper.selectOne(any())).thenReturn(u);
        Result<String> res = service.roleOf(10L);
        assertEquals(0, res.code);
        assertEquals("ADMIN", res.data);
    }

    @Test
    @DisplayName("roleOf：查无此人返回 40400（调用方按非 ADMIN 拒绝=fail-closed）")
    void roleOfMissingUserIs404() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertNotEquals(0, service.roleOf(999L).code);
        assertEquals(40400, service.roleOf(999L).code);
    }

    @Test
    @DisplayName("roleOf：角色列为空按 USER 返回（无角色数据默认 USER）")
    void roleOfNullRoleDefaultsUser() {
        User u = new User();
        u.setId(3L);
        u.setRole(null);
        when(userMapper.selectOne(any())).thenReturn(u);
        Result<String> res = service.roleOf(3L);
        assertEquals(0, res.code);
        assertTrue("USER".equals(res.data));
    }
}

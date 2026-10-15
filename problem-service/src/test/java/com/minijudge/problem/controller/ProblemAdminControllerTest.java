package com.minijudge.problem.controller;

import com.minijudge.common.Result;
import com.minijudge.problem.entity.Problem;
import com.minijudge.problem.entity.TestCase;
import com.minijudge.problem.mapper.ProblemMapper;
import com.minijudge.problem.mapper.TestCaseMapper;
import com.minijudge.problem.mapper.UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建题/传用例接口 RBAC 收口的回归防线（背景：计划书要求 ADMIN 专属，
 * 收口前任何登录用户可越权写题库）：
 * 1. 普通用户/缺身份头/无角色数据/角色查询异常 → 一律 40300，题库纹丝不动；
 * 2. 鉴权先于参数校验（避免越权者借 400xx 错误差异探测业务规则）；
 * 3. ADMIN 放行后业务行为与原契约一致（建题返回 id、用例落盘）。
 */
@ExtendWith(MockitoExtension.class)
class ProblemAdminControllerTest {

    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private TestCaseMapper testCaseMapper;
    @Mock
    private UserRoleMapper userRoleMapper;

    private ProblemAdminController controller;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        controller = new ProblemAdminController(problemMapper, testCaseMapper, userRoleMapper);
        ReflectionTestUtils.setField(controller, "casesRoot", tmp.toString());
    }

    private ProblemAdminController.CreateProblemReq validReq() {
        ProblemAdminController.CreateProblemReq r = new ProblemAdminController.CreateProblemReq();
        r.setTitle("A+B");
        r.setDescription("输入两个整数输出和");
        return r;
    }

    private ProblemAdminController.UploadCasesReq oneCase() {
        ProblemAdminController.CaseReq c = new ProblemAdminController.CaseReq();
        c.setInputBase64("MSAyCg==");      // "1 2\n"
        c.setOutputBase64("Mwo=");          // "3\n"
        ProblemAdminController.UploadCasesReq r = new ProblemAdminController.UploadCasesReq();
        r.setCases(List.of(c));
        return r;
    }

    // ── 建题 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("缺身份头建题 → 40300（绕过网关裸连也进不来）")
    void createWithoutIdentityRejected() {
        Result<Map<String, Object>> res = controller.create(null, validReq());
        assertEquals(40300, res.code);
        verify(problemMapper, never()).insert(any(Problem.class));
    }

    @Test
    @DisplayName("普通用户建题 → 40300，t_problem 无写入")
    void createByNormalUserRejected() {
        when(userRoleMapper.selectRoleById(2L)).thenReturn("USER");
        Result<Map<String, Object>> res = controller.create(2L, validReq());
        assertEquals(40300, res.code);
        verify(problemMapper, never()).insert(any(Problem.class));
    }

    @Test
    @DisplayName("无角色数据（role 列为 NULL）→ 默认 USER，建题拒绝 40300")
    void createWithNullRoleDefaultsToUser() {
        when(userRoleMapper.selectRoleById(3L)).thenReturn(null);
        assertEquals(40300, controller.create(3L, validReq()).code);
        verify(problemMapper, never()).insert(any(Problem.class));
    }

    @Test
    @DisplayName("角色查询抛异常 → fail-closed 40300，不放行")
    void createFailsClosedWhenRoleQueryThrows() {
        when(userRoleMapper.selectRoleById(anyLong())).thenThrow(new RuntimeException("db down"));
        assertEquals(40300, controller.create(9L, validReq()).code);
        verify(problemMapper, never()).insert(any(Problem.class));
    }

    @Test
    @DisplayName("鉴权先于参数校验：普通用户即使参数非法也只见 40300，不见 400xx")
    void authCheckedBeforeValidation() {
        when(userRoleMapper.selectRoleById(2L)).thenReturn("USER");
        ProblemAdminController.CreateProblemReq bad = new ProblemAdminController.CreateProblemReq();
        bad.setTitle("");   // 若先校验会报 40001——泄露业务规则给越权探测者
        Result<Map<String, Object>> res = controller.create(2L, bad);
        assertEquals(40300, res.code);
    }

    @Test
    @DisplayName("ADMIN 建题 → 正常入库返回 id（RBAC 不破原有业务契约）")
    void createByAdminSucceeds() {
        when(userRoleMapper.selectRoleById(1L)).thenReturn("ADMIN");
        when(problemMapper.insert(any(Problem.class))).thenAnswer(inv -> {
            inv.getArgument(0, Problem.class).setId(42L);
            return 1;
        });
        Result<Map<String, Object>> res = controller.create(1L, validReq());
        assertEquals(0, res.code);
        assertEquals(42L, res.data.get("id"));
        verify(problemMapper).insert(any(Problem.class));
    }

    // ── 传用例 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("普通用户上传用例 → 40300，不查题不落盘")
    void uploadCasesByNormalUserRejected() {
        when(userRoleMapper.selectRoleById(2L)).thenReturn("USER");
        Result<Map<String, Object>> res = controller.uploadCases(2L, 1L, oneCase());
        assertEquals(40300, res.code);
        verify(problemMapper, never()).selectById(anyLong());
        verify(testCaseMapper, never()).insert(any(TestCase.class));
    }

    @Test
    @DisplayName("缺身份头上传用例 → 40300")
    void uploadCasesWithoutIdentityRejected() {
        assertEquals(40300, controller.uploadCases(null, 1L, oneCase()).code);
        verify(testCaseMapper, never()).insert(any(TestCase.class));
    }

    @Test
    @DisplayName("ADMIN 上传用例 → 落盘 + 元数据入库（原契约不变）")
    void uploadCasesByAdminSucceeds() {
        when(userRoleMapper.selectRoleById(1L)).thenReturn("ADMIN");
        when(problemMapper.selectById(7L)).thenReturn(new Problem());
        Result<Map<String, Object>> res = controller.uploadCases(1L, 7L, oneCase());
        assertEquals(0, res.code);
        assertEquals(1, res.data.get("uploaded"));
        verify(testCaseMapper).insert(any(TestCase.class));
        try {
            // 落盘内容抽查：1 号用例输入应为解码后的 "1 2\n"
            String in = Files.readString(tmp.resolve("7").resolve("1.in"), StandardCharsets.UTF_8);
            assertEquals("1 2\n", in);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("ADMIN 但题不存在 → 40400（放行后业务校验仍按原样生效）")
    void uploadCasesAdminMissingProblem() {
        when(userRoleMapper.selectRoleById(1L)).thenReturn("ADMIN");
        when(problemMapper.selectById(anyLong())).thenReturn(null);
        Result<Map<String, Object>> res = controller.uploadCases(1L, 999L, oneCase());
        assertEquals(40400, res.code);
        assertTrue(res.msg.contains("题目不存在"));
    }
}

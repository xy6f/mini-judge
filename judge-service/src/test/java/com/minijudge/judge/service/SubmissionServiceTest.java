package com.minijudge.judge.service;

import com.minijudge.common.Result;
import com.minijudge.judge.client.ProblemClient;
import com.minijudge.judge.client.UserClient;
import com.minijudge.judge.client.dto.ProblemInfo;
import com.minijudge.judge.entity.Submission;
import com.minijudge.judge.mapper.SubmissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交入口校验的单测（对应全量测试 H1/H6 两个缺陷的回归防线）：
 * 1. 空/空白代码必须被 40001 拦截（修复前 "" 漏网入队）；
 * 2. 不存在的 problemId 必须被 40400 拒绝入队（修复前入队后无限 REQUEUE 死循环）；
 * 3. 题库服务临时不可用（Feign 抛异常）不能误判成"题目不存在"，仍放行入队。
 *
 * <p>RBAC 收口新增：rejudge 只有 ADMIN 可执行（现查 user-service），
 * 普通用户/查无角色/缺身份头/角色服务不可用全部 40300 拒绝且不触发状态流转。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock
    private SubmissionMapper submissionMapper;
    @Mock
    private ProblemClient problemClient;
    @Mock
    private UserClient userClient;

    @InjectMocks
    private SubmissionService service;

    private SubmissionService.SubmitReq req(String language, String code, Long problemId) {
        SubmissionService.SubmitReq r = new SubmissionService.SubmitReq();
        r.setLanguage(language);
        r.setCode(code);
        r.setProblemId(problemId);
        return r;
    }

    @Test
    @DisplayName("空字符串代码被拦截 40001（回归：修复前空代码可入队）")
    void emptyCodeRejected() {
        Result<Object> res = service.submit(1L, req("JAVA17", "", 1L));
        assertEquals(40001, res.code);
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @Test
    @DisplayName("纯空白代码同样被拦截 40001")
    void blankCodeRejected() {
        Result<Object> res = service.submit(1L, req("JAVA17", "   \n  ", 1L));
        assertEquals(40001, res.code);
    }

    @Test
    @DisplayName("超过 64KB 的代码被拦截 40001")
    void oversizedCodeRejected() {
        Result<Object> res = service.submit(1L, req("JAVA17", "a".repeat(64 * 1024 + 1), 1L));
        assertEquals(40001, res.code);
    }

    @Test
    @DisplayName("不支持的语言被拦截 40002")
    void unsupportedLanguageRejected() {
        Result<Object> res = service.submit(1L, req("PYTHON3", "print(1)", 1L));
        assertEquals(40002, res.code);
    }

    @Test
    @DisplayName("problemId 为空被拦截 40003")
    void nullProblemIdRejected() {
        Result<Object> res = service.submit(1L, req("JAVA17", "class Main{}", null));
        assertEquals(40003, res.code);
    }

    @Test
    @DisplayName("题目不存在：明确报 40400 且绝不入队（回归：修复前会无限 REQUEUE）")
    void nonexistentProblemRejectedAtSubmit() {
        when(problemClient.getProblem(anyLong()))
                .thenReturn(Result.fail(40400, "题目不存在"));
        Result<Object> res = service.submit(1L, req("JAVA17", "class Main{}", 999999L));
        assertEquals(40400, res.code);
        verify(submissionMapper, never()).insert(any(Submission.class));
    }

    @Test
    @DisplayName("题库服务临时不可用（Feign 抛异常）不误判为不存在，放行入队")
    void problemServiceDownStillEnqueues() {
        when(problemClient.getProblem(anyLong())).thenThrow(new RuntimeException("connect refused"));
        Result<Object> res = service.submit(1L, req("JAVA17", "class Main{}", 1L));
        assertEquals(0, res.code);
        verify(submissionMapper).insert(any(Submission.class));
    }

    @Test
    @DisplayName("正常提交：题目存在则入队成功")
    void normalSubmitEnqueues() {
        ProblemInfo info = new ProblemInfo();
        info.setId(1L);
        when(problemClient.getProblem(anyLong())).thenReturn(Result.ok(info));
        when(submissionMapper.selectCount(any())).thenReturn(0L);
        Result<Object> res = service.submit(1L, req("JAVA17", "class Main{}", 1L));
        assertEquals(0, res.code);
        verify(submissionMapper).insert(any(Submission.class));
    }

    // ── rejudge RBAC 收口（ADMIN 专属，非 ADMIN/查不到角色一律 40300）────

    @Test
    @DisplayName("ADMIN rejudge 对不存在的提交明确报 40400")
    void rejudgeMissingSubmission() {
        when(userClient.getRole(1L)).thenReturn(Result.ok("ADMIN"));
        when(submissionMapper.selectById(anyLong())).thenReturn(null);
        Result<Void> res = service.rejudge(999999L, 1L);
        assertEquals(40400, res.code);
    }

    @Test
    @DisplayName("普通用户 rejudge → 40300，且不碰提交表（先验权后查库，不泄露存在性）")
    void rejudgeByNormalUserRejected() {
        when(userClient.getRole(2L)).thenReturn(Result.ok("USER"));
        Result<Void> res = service.rejudge(1L, 2L);
        assertEquals(40300, res.code);
        verify(submissionMapper, never()).selectById(anyLong());
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    @DisplayName("无身份头（绕过网关裸连）→ 40300，连角色查询都不发起")
    void rejudgeWithoutIdentityRejected() {
        Result<Void> res = service.rejudge(1L, null);
        assertEquals(40300, res.code);
        verify(userClient, never()).getRole(anyLong());
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    @DisplayName("user-service 不可用（Feign 抛异常）→ fail-closed 40300，绝不放行")
    void rejudgeFailsClosedWhenUserServiceDown() {
        when(userClient.getRole(3L)).thenThrow(new RuntimeException("connect refused"));
        Result<Void> res = service.rejudge(1L, 3L);
        assertEquals(40300, res.code);
        verify(submissionMapper, never()).updateById(any(Submission.class));
    }

    @Test
    @DisplayName("角色答复为查无此人（code!=0）→ 按非 ADMIN 拒绝 40300")
    void rejudgeUnknownUserRejected() {
        when(userClient.getRole(4L)).thenReturn(Result.fail(40400, "用户不存在"));
        Result<Void> res = service.rejudge(1L, 4L);
        assertEquals(40300, res.code);
    }

    @Test
    @DisplayName("无角色数据（code=0 但 data 为空）→ 默认 USER，拒绝 40300")
    void rejudgeNullRoleDataDefaultsToUser() {
        when(userClient.getRole(5L)).thenReturn(Result.ok(null));
        Result<Void> res = service.rejudge(1L, 5L);
        assertEquals(40300, res.code);
    }

    @Test
    @DisplayName("ADMIN rejudge 存在提交 → 置 REQUEUE 走状态流转")
    void rejudgeByAdminFlipsStatus() {
        when(userClient.getRole(1L)).thenReturn(Result.ok("ADMIN"));
        Submission s = new Submission();
        s.setId(1L);
        s.setStatus("DONE");
        when(submissionMapper.selectById(1L)).thenReturn(s);
        Result<Void> res = service.rejudge(1L, 1L);
        assertEquals(0, res.code);
        ArgumentCaptor<Submission> cap = ArgumentCaptor.forClass(Submission.class);
        verify(submissionMapper).updateById(cap.capture());
        assertEquals("REQUEUE", cap.getValue().getStatus());
    }
}

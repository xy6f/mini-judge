package com.minijudge.judge.controller;

import com.minijudge.common.Result;
import com.minijudge.judge.mapper.SubmissionMapper;
import com.minijudge.judge.engine.JudgeWorkerPool;
import com.minijudge.judge.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * judge-service 对外接口（计划书第 6 章）。
 * 用户身份：只信网关注入的 X-User-Id 头（网关已验证 JWT 并覆盖伪造值，见 JwtGlobalFilter）。
 */
@RestController
@RequiredArgsConstructor
public class SubmissionController {

    private final SubmissionService submissionService;
    private final SubmissionMapper submissionMapper;
    private final JudgeWorkerPool workerPool;

    @PostMapping("/api/submissions")
    public Result<Object> submit(@RequestHeader("X-User-Id") Long userId,
                                 @RequestBody SubmissionService.SubmitReq req) {
        return submissionService.submit(userId, req);
    }

    @GetMapping("/api/submissions/{id}")
    public Result<Object> detail(@PathVariable Long id) {
        return submissionService.detail(id);
    }

    @GetMapping("/api/submissions")
    public Result<java.util.List<com.minijudge.judge.entity.Submission>> history(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long problemId) {
        return submissionService.history(userId, problemId);
    }

    /**
     * 管理端重判（ADMIN 专属，计划书第 6 章 RBAC 收口）。
     * required=false：缺身份头不再 400 裸异常，而是进服务层按 fail-closed 拒绝，
     * 错误语义统一（40300），也堵住"绕过网关直连 8103 无头调用"的口子。
     */
    @PostMapping("/api/submissions/{id}/rejudge")
    public Result<Void> rejudge(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                @PathVariable Long id) {
        return submissionService.rejudge(id, userId);
    }

    /**
     * 评测机状态（计划书第 6 章 /judge/status）。
     * 这个接口的消费者是"人和项目四的 AIOps Agent"——机器可读的状态接口是
     * 可观测性的第一步，特意把队列深度/worker 忙闲暴露成指标。
     */
    @GetMapping("/api/judge/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(Map.of(
                "queued", submissionMapper.countQueued(),
                "running", submissionMapper.countRunning(),
                "workerBusy", workerPool.busyCount(),
                "workerCapacity", workerPool.capacity()));
    }
}

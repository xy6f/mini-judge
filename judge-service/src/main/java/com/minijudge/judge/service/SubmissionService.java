package com.minijudge.judge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.minijudge.common.Result;
import com.minijudge.judge.client.ProblemClient;
import com.minijudge.judge.client.UserClient;
import com.minijudge.judge.client.dto.ProblemInfo;
import com.minijudge.judge.entity.Submission;
import com.minijudge.judge.mapper.SubmissionMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交业务（计划书第 6/7 章）。
 *
 * <p>核心原则（面试点）：提交接口"秒回"——只做校验+入库，绝不在请求线程里
 * 评测。这正是异步化的意义：把慢操作从用户等待路径上摘下来，压测对比
 * "同步评测 vs 异步入队"的接口 P99 就是简历数字（计划书 1.2）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    /** 源码大小护栏：64KB，防刷内存/磁盘（无鉴权细节时先粗挡） */
    private static final int MAX_CODE_BYTES = 64 * 1024;

    private final SubmissionMapper submissionMapper;
    private final ProblemClient problemClient;
    private final UserClient userClient;

    @Data
    public static class SubmitReq {
        private Long problemId;
        private String language;   // JAVA17 / CPP17
        private String code;
    }

    public Result<Object> submit(Long userId, SubmitReq req) {
        // 根因修复（全量测试 H1）：原判断只挡 null 和超长，空串 ""（length=0）漏网入队，
        // 后续评测拿空源码浪费 worker。isBlank 一并覆盖纯空白代码。
        if (req.getCode() == null || req.getCode().isBlank()
                || req.getCode().length() > MAX_CODE_BYTES) {
            return Result.fail(40001, "代码为空或超过 64KB");
        }
        if (!List.of("JAVA17", "CPP17").contains(req.getLanguage())) {
            return Result.fail(40002, "不支持的语言");
        }
        // 根因修复（全量测试 H6）：不存在的 problemId 原本照常入队，worker 拉题失败后
        // 按"系统故障"REQUEUE，而题目不存在是确定性错误、重跑永远失败——
        // Dispatcher 每轮 REQUEUE->QUEUED 导致无限重判死循环（实测刷爆 t_judge_log）。
        // 提交入口先经题库确认题目存在，不存在直接返回明确错误拒绝入队。
        // 注意只判"题库明确答复不存在"（code=40400）；题库服务临时不可用
        // （网络异常）仍放行入队，交给 worker 侧的故障 REQUEUE 重试语义，不误伤正常提交。
        if (req.getProblemId() == null) {
            return Result.fail(40003, "problemId 不能为空");
        }
        try {
            Result<ProblemInfo> pr = problemClient.getProblem(req.getProblemId());
            if (pr != null && pr.code == 40400) {
                return Result.fail(40400, "题目不存在: " + req.getProblemId());
            }
            // GUI 后回归验证抓到的同类缺口：题存在但没有任何测试用例时，
            // worker 抛"基础设施异常"→REQUEUE→无限重判（确定性配置错误走了重试通道）。
            // 入口直接拒收，给用户即时可懂的错误；worker 侧另做终态兜底（双保险）。
            Result<java.util.List<com.minijudge.judge.client.dto.CaseInfo>> cs =
                    problemClient.getCases(req.getProblemId());
            if (cs != null && cs.code == 0 && (cs.data == null || cs.data.isEmpty())) {
                return Result.fail(40004, "题目尚无测试用例，暂不可提交（请出题人先上传用例）");
            }
        } catch (Exception e) {
            // Feign 调用异常=题库暂时不可用，不属于"题目不存在"，保持原入队行为
            log.debug("problem existence check skipped, problemClient unavailable: {}", e.getMessage());
        }
        Submission s = new Submission();
        s.setUserId(userId);
        s.setProblemId(req.getProblemId());
        s.setLanguage(req.getLanguage());
        s.setCode(req.getCode());
        s.setStatus("QUEUED");     // 入队即返回，Dispatcher 稍后领取
        s.setSubmitTime(LocalDateTime.now());
        submissionMapper.insert(s);

        // 队列位置：FIFO 下前面还有几条 QUEUED（粗略值，高并发下仅供展示）
        long position = submissionMapper.selectCount(
                Wrappers.<Submission>lambdaQuery()
                        .eq(Submission::getStatus, "QUEUED")
                        .lt(Submission::getSubmitTime, s.getSubmitTime()));
        return Result.ok(new java.util.HashMap<String, Object>() {{
            put("submissionId", s.getId());
            put("status", "QUEUED");
            put("queuePosition", position);
        }});
    }

    /** 结果详情：WS 推送的拉取兜底 */
    public Result<Object> detail(Long id) {
        Submission s = submissionMapper.selectById(id);
        if (s == null) {
            return Result.fail(40400, "提交不存在");
        }
        // 回显裁剪：code 太大不返回全文（只给长度），其余字段给前端渲染
        java.util.Map<String, Object> view = new java.util.HashMap<>();
        view.put("id", s.getId());
        view.put("problemId", s.getProblemId());
        view.put("language", s.getLanguage());
        view.put("status", s.getStatus());
        view.put("resultCode", s.getResultCode());
        view.put("passedCases", s.getPassedCases());
        view.put("totalCases", s.getTotalCases());
        view.put("timeUsedMs", s.getTimeUsedMs());
        view.put("memoryUsedKb", s.getMemoryUsedKb());
        view.put("errorMsg", s.getErrorMsg());
        view.put("submitTime", s.getSubmitTime());
        return Result.ok(view);
    }

    public Result<List<Submission>> history(Long userId, Long problemId) {
        var q = Wrappers.<Submission>lambdaQuery();
        if (userId != null) q.eq(Submission::getUserId, userId);
        if (problemId != null) q.eq(Submission::getProblemId, problemId);
        return Result.ok(submissionMapper.selectList(
                q.orderByDesc(Submission::getSubmitTime).last("LIMIT 50")));
    }

    /**
     * 管理端重判（RBAC 收口：ADMIN 服务端强校验，非 ADMIN 一律 40300）。
     *
     * <p>校验方式选"现查 user-service"而非信 JWT/网关头里的角色：
     * 令牌是签发时刻的快照（720 分钟），撤权后旧令牌里的 ADMIN 会一直生效到过期；
     * 现查 t_user 则撤权即时生效。角色查询失败（用户不存在/服务不可用）
     * 按 fail-closed 处理——宁可拒绝一次合法重判，不可放过一次越权操作。</p>
     */
    public Result<Void> rejudge(Long submissionId, Long userId) {
        // 先验权后查库：避免越权者用 40400/成功差异探测哪些提交存在
        if (!isAdmin(userId)) {
            return Result.fail(40300, "需要 ADMIN 角色，普通用户无权重判");
        }
        Submission s = submissionMapper.selectById(submissionId);
        if (s == null) {
            return Result.fail(40400, "提交不存在");
        }
        // 置 REQUEUE：Dispatcher 恢复轮会转成 QUEUED 重入队（保留历史 result 供对比）
        s.setStatus("REQUEUE");
        submissionMapper.updateById(s);
        return Result.ok(null);
    }

    /**
     * 服务端现查角色判定 ADMIN（rejudge 等管理操作专用）。
     * fail-closed 三连：身份头缺失(userId=null，绕过网关直连的裸请求)、
     * 角色服务调用异常、答复非成功/角色为空——统统按"非 ADMIN"处理，
     * 与 JwtSupport 对无 role claim 旧令牌兜底 USER 是同一最小权限原则。
     */
    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            Result<String> r = userClient.getRole(userId);
            if (r == null || r.code != 0 || r.data == null || r.data.isBlank()) {
                return false;   // 查无此人或答复异常：按 USER 拒绝
            }
            return "ADMIN".equalsIgnoreCase(r.data.trim());
        } catch (Exception e) {
            log.warn("role check unavailable for user {}, reject by fail-closed: {}",
                    userId, e.getMessage());
            return false;
        }
    }
}

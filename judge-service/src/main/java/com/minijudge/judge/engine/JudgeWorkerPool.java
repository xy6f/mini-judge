package com.minijudge.judge.engine;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.minijudge.common.Result;
import com.minijudge.judge.client.ProblemClient;
import com.minijudge.judge.client.dto.CaseInfo;
import com.minijudge.judge.client.dto.ProblemInfo;
import com.minijudge.judge.entity.JudgeLog;
import com.minijudge.judge.entity.Submission;
import com.minijudge.judge.mapper.JudgeLogMapper;
import com.minijudge.judge.mapper.SubmissionMapper;
import com.minijudge.judge.push.ResultPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测 worker 池（计划书 7.2）。
 *
 * <p>并发模型：信号量 permits = 可用核数，"一个 worker 同时只跑一个任务"，
 * 比 ThreadPoolExecutor 更贴合"任务寿命长、要显式背压"的评测场景——
 * Dispatcher 拿不到 permit 就不会领任务，队列自然积压在 DB（面试对比线程池方案）。</p>
 *
 * <p>单任务执行全流程（execute，计划书第 8 章时序的落地）：
 * 1. Feign 拉题目护栏参数 + 全部用例元信息（失败=JudgeInfraException，REQUEUE）；
 * 2. 为提交建专属工作目录 {work-root}/{submissionId}/，源码写 Main.java
 *    （MVP 约定类名必须为 Main，洛谷同款约定；解析类名记为演进项）；
 * 3. 按 sandbox.mode 分派：simulate=本机 javac 编译 + 逐用例 java 执行；
 *    docker=一次容器运行，容器内编译执行比对，宿主解析单行摘要；
 * 4. 判分聚合：编译失败 CE 短路；逐用例 TLE/RE/MLE/WA/AC；
 *    fail-fast=true 首个失败即停（默认，省算力），false 跑完出全用例详情（可配置，计划书 8）；
 * 5. 写 t_judge_log 全时间线 + 条件更新 t_submission 终态 + WebSocket 推送。</p>
 *
 * <p>公正性铁律（面试点）：评测器自身故障（拉题失败/用例文件缺失/沙箱脚本异常）
 * 一律 REQUEUE 重跑，绝不降级成用户代码的 WA/RE——"系统错了不让用户背锅"，
 * 判分幂等保证重跑无害。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeWorkerPool {

    /** docker 模式容器内 judge-run.sh 的摘要协议行（协议定义见该脚本头部注释） */
    private static final Pattern DOCKER_SUMMARY =
            Pattern.compile("VERDICT=(\\w+)\\s+PASSED=(\\d+)\\s+TOTAL=(\\d+)\\s+TIME=(\\d+)");

    /** error_msg 列宽 1024，入库前截断并标记，防超长 stderr 撑爆列 */
    private static final int MAX_ERROR_MSG = 1000;

    private final SubmissionMapper submissionMapper;
    private final SandboxRunner sandboxRunner;
    private final ResultPushService pushService;
    private final ProblemClient problemClient;
    private final JudgeLogMapper judgeLogMapper;

    /** 并行度默认=核数（容器 --cpus 1 正好一核一任务） */
    @Value("${mini-judge.judge.concurrency:4}")
    private int concurrency;

    /** 提交工作目录根：相对路径按服务进程工作目录解析（local 冒烟=judge-service/work） */
    @Value("${mini-judge.judge.work-root:work}")
    private String workRoot = "work";

    /** 失败短路策略：true=首个失败用例即停；false=跑完全部用例统计 pass 数（计划书 8 章可配置项） */
    @Value("${mini-judge.judge.fail-fast:true}")
    private boolean failFast = true;

    /** 编译阶段超时：编译比运行更易被恶意输入拖死（泛型炸弹等），独立给足但必须有限 */
    @Value("${mini-judge.judge.compile-timeout-ms:15000}")
    private int compileTimeoutMs = 15000;

    /** 调试开关：true 时判完不清理工作目录，便于本地翻 case_stdout.log 排查 */
    @Value("${mini-judge.judge.keep-work-dir:false}")
    private boolean keepWorkDir = false;

    private Semaphore permits;
    /** 执行线程池：大小=concurrency，一 permit 对一线程，语义与信号量一致 */
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger busyCount = new AtomicInteger();
    /** 线程序号：命名 judge-worker-N，修复 W1 骨架里所有线程同名 hashCode 的排查盲区 */
    private final AtomicInteger threadSeq = new AtomicInteger();

    /** 懒初始化：@Value 注入在构造之后，不能放构造器里建池 */
    private synchronized void initPool() {
        if (permits == null) {
            permits = new Semaphore(concurrency);
            // 命名线程便于 jstack 排查（"judge-worker-3 在等容器"这种现场可读性）
            executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency,
                    r -> new Thread(r, "judge-worker-" + threadSeq.incrementAndGet()));
        }
    }

    /** 领取成功后异步执行：调度线程不等评测结果 */
    public void submit(Submission s) {
        initPool();
        executor.submit(() -> execute(s));
    }

    /** Dispatcher 领了 permit 却发现无任务：必须归还，否则空占导致 worker 池"慢性死亡" */
    public void releaseUnusedPermit() {
        initPool();
        permits.release();
    }

    public boolean available() {
        initPool();
        return permits.tryAcquire();
    }

    public int busyCount() {
        return busyCount.get();
    }

    public int capacity() {
        return concurrency;
    }

    /**
     * 执行一条已领取(RUNNING)的提交。整体 try/finally 归还 permit——
     * 任何环节抛异常都不允许"信号量泄漏"导致 worker 越来越少（生产事故级细节）。
     *
     * <p>异常分层（公正性核心）：
     * JudgeInfraException/其它异常 -> REQUEUE（系统故障，重跑）；
     * InterruptedException        -> REQUEUE（worker 被打断，重跑）；
     * 用户代码错误不抛异常，而是 Outcome 里的 CE/TLE/MLE/RE/WA 判定码。</p>
     */
    public void execute(Submission s) {
        busyCount.incrementAndGet();
        Path workDir = null;
        try {
            // 1. 拉题面护栏参数与全部用例（服务间内网调用，失败即系统故障）
            ProblemInfo problem = fetchProblem(s.getProblemId());
            List<CaseInfo> cases = fetchCases(s.getProblemId());
            if (cases.isEmpty()) {
                // 修复（回归验证抓到）：原抛 JudgeInfraException 会被外层按"系统故障"
                // REQUEUE——但"题无用例"是确定性配置错误，重跑一万次也不会出现用例，
                // 结果是无限重判刷爆 t_judge_log。确定性错误必须落终态：
                // DONE + ERROR 判定码（区别于用户代码的 CE/WA，这是题目配置问题）。
                // 提交入口已加拒收校验，这里是"入队后用例被删"等竞态的兜底。
                s.setStatus("DONE");
                s.setResultCode("ERROR");
                s.setErrorMsg("题目没有测试用例，无法判分（请联系出题人）");
                s.setJudgedTime(java.time.LocalDateTime.now());
                submissionMapper.updateById(s);
                logJudge(s.getId(), "NO_CASES_TERMINAL", "problem=" + s.getProblemId());
                pushService.pushResult(s);
                return;
            }

            // 2. 该提交专属工作目录 + 源码落盘
            workDir = Paths.get(workRoot, String.valueOf(s.getId())).toAbsolutePath().normalize();
            Files.createDirectories(workDir);
            Path sourceFile = workDir.resolve("Main.java");
            Files.writeString(sourceFile, s.getCode(), StandardCharsets.UTF_8);
            logJudge(s.getId(), "DISPATCHED", "problem=" + s.getProblemId()
                    + " cases=" + cases.size() + " sandbox=" + (sandboxRunner.isSimulate() ? "simulate" : "docker"));

            // 3. 按模式执行评测（判定码在工作方法内部产出，这里只汇收）
            Outcome outcome = sandboxRunner.isSimulate()
                    ? judgeSimulate(s, workDir, problem, cases)
                    : judgeDocker(s, workDir, problem, cases);

            // 4. 聚合写回 + 推送
            finish(s, outcome);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("submission {} interrupted, requeue", s.getId());
            requeue(s, "INTERRUPTED", "worker 线程被中断");
        } catch (Exception e) {
            // 评测器自身故障与用户代码无关：标 REQUEUE 重跑，绝不冤枉判 WA（公正性，计划书 3.2）
            log.error("judge infra error on submission {}", s.getId(), e);
            requeue(s, "INFRA_ERROR", e.getMessage());
        } finally {
            cleanupWorkDir(workDir);
            busyCount.decrementAndGet();
            if (permits != null) {
                permits.release();
            }
        }
    }

    // ===================== simulate：本机 JDK 逐用例执行 =====================

    /**
     * simulate 模式评测：javac 编译（CE 短路）-> 逐用例 java 执行 -> 比对判分。
     * 判定优先级：timedOut=TLE > 进程非零退出（crashVerdict：先查 OOM 关键字
     * 判 MLE，查不到才判 RE）> 比对不等=WA > AC。
     */
    private Outcome judgeSimulate(Submission s, Path workDir, ProblemInfo problem,
                                  List<CaseInfo> cases) throws IOException, InterruptedException {
        // simulate 只有本机 JDK，跑不了 C++：开发期误提交 CPP17 给明确提示而非闷头重跑
        if (!"JAVA17".equals(s.getLanguage())) {
            return new Outcome("RE", 0, cases.size(), 0,
                    "dev simulate 模式暂只能评 JAVA17（C++ 需 docker 模式，W3 镜像）");
        }
        int timeLimit = problem.getTimeLimitMs() == null ? 2000 : problem.getTimeLimitMs();
        int memKb = problem.getMemoryLimitKb() == null ? 262144 : problem.getMemoryLimitKb();

        Path classesDir = workDir.resolve("out");
        SandboxRunner.RunResult compile = sandboxRunner.simulateCompile(
                workDir, workDir.resolve("Main.java"), classesDir, compileTimeoutMs);
        if (compile.timedOut()) {
            logJudge(s.getId(), "COMPILED", "CE compile timeout");
            return new Outcome("CE", 0, cases.size(), 0, "编译超时");
        }
        if (compile.exitCode() != 0) {
            // CE 短路：编译都过不了，不必浪费 N 次用例执行（计划书第 8 章流程）
            logJudge(s.getId(), "COMPILED", "CE exit=" + compile.exitCode());
            return new Outcome("CE", 0, cases.size(), 0, trimMsg(compile.stderr()));
        }
        logJudge(s.getId(), "COMPILED", "ok");

        int passed = 0;
        long maxTimeMs = 0;
        String firstFail = null;
        String errorMsg = null;
        int idx = 0;
        for (CaseInfo ca : cases) {
            idx++;
            int order = ca.getOrderNo() == null ? idx : ca.getOrderNo();
            Path inputFile = Paths.get(ca.getInputPath());
            Path expectFile = Paths.get(ca.getOutputPath());
            if (!Files.isReadable(inputFile) || !Files.isReadable(expectFile)) {
                // 用例文件读不到=题库数据问题，是系统故障不是用户错（REQUEUE）
                throw new JudgeInfraException("用例 #" + order + " 文件缺失或不可读: "
                        + inputFile + " / " + expectFile);
            }
            long t0 = System.nanoTime();
            SandboxRunner.RunResult r = sandboxRunner.simulateRun(
                    workDir, classesDir, "Main", inputFile, timeLimit, memKb);
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            maxTimeMs = Math.max(maxTimeMs, tookMs);

            String verdict;
            if (r.timedOut()) {
                verdict = "TLE";
            } else if (r.exitCode() != 0) {
                // 非零退出统一走 crashVerdict：先查 OOM 关键字判 MLE，查不到才判 RE
                verdict = crashVerdict(r.stderr());
                errorMsg = trimMsg(r.stderr());
            } else {
                String expected = Files.readString(expectFile, StandardCharsets.UTF_8);
                verdict = OutputComparator.matches(r.stdout(), expected) ? "AC" : "WA";
            }
            logJudge(s.getId(), "CASE_" + order, verdict + " time=" + tookMs + "ms");

            if ("AC".equals(verdict)) {
                passed++;
            } else if (firstFail == null) {
                firstFail = verdict;
            }
            if (failFast && !"AC".equals(verdict)) {
                break;   // 短路策略：首个失败即停，后面用例不再耗算力
            }
        }
        String result = firstFail == null ? "AC" : firstFail;
        return new Outcome(result, passed, cases.size(), maxTimeMs, errorMsg);
    }

    // ===================== docker：一次容器运行，摘要协议解析 =====================

    /**
     * docker 模式评测：容器内 judge-run.sh 完成编译+逐用例执行+比对（用例数据
     * 全程不出容器，杜绝大输出过管道），宿主只拿一行摘要：
     * VERDICT=xx PASSED=p TOTAL=t TIME=ms。摘要缺失=沙箱脚本自身异常 -> 系统故障 REQUEUE。
     */
    private Outcome judgeDocker(Submission s, Path workDir, ProblemInfo problem,
                                List<CaseInfo> cases) throws IOException, InterruptedException {
        int timeLimit = problem.getTimeLimitMs() == null ? 2000 : problem.getTimeLimitMs();
        int memKb = problem.getMemoryLimitKb() == null ? 262144 : problem.getMemoryLimitKb();
        Path caseDir = Paths.get(cases.get(0).getInputPath()).getParent();
        if (caseDir == null) {
            throw new JudgeInfraException("用例路径异常: " + cases.get(0).getInputPath());
        }
        SandboxRunner.RunResult r = sandboxRunner.run(
                s.getLanguage(), workDir.toString(), caseDir.toString(), timeLimit, memKb);
        if (r.timedOut()) {
            // 宿主兜底超时：容器整体 overrun（含 docker 客户端挂死），判 TLE 并强杀过
            return new Outcome("TLE", 0, cases.size(), 0, trimMsg(r.stderr()));
        }
        Matcher m = DOCKER_SUMMARY.matcher(r.stdout());
        if (!m.find()) {
            throw new JudgeInfraException("沙箱摘要协议缺失 exit=" + r.exitCode()
                    + " stdout=" + trimMsg(r.stdout()) + " stderr=" + trimMsg(r.stderr()));
        }
        return new Outcome(m.group(1), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Long.parseLong(m.group(4)), null);
    }

    // ===================== 结果落库/推送/辅助 =====================

    /**
     * 聚合写回终态。双重守卫（并发正确性，面试点）：
     * 1. 状态机断言：RUNNING->DONE 非法即抛——纯逻辑防线；
     * 2. SQL 条件 CAS：UPDATE ... WHERE status='RUNNING'——看门狗若已把本任务
     *    置 REQUEUE（认定执行者丢失），这里 affected=0，本次结果作废重跑，
     *    绝不把迟到结果盖回终态。
     */
    private void finish(Submission s, Outcome o) {
        JudgeStateMachine.assertTransition(JudgeStateMachine.RUNNING, JudgeStateMachine.DONE);
        int n = submissionMapper.update(null,
                Wrappers.<Submission>lambdaUpdate()
                        .set(Submission::getStatus, "DONE")
                        .set(Submission::getResultCode, o.resultCode())
                        .set(Submission::getPassedCases, o.passed())
                        .set(Submission::getTotalCases, o.total())
                        .set(Submission::getTimeUsedMs, o.timeUsedMs())
                        .set(Submission::getErrorMsg, o.errorMsg())
                        .set(Submission::getJudgedTime, LocalDateTime.now())
                        .eq(Submission::getId, s.getId())
                        .eq(Submission::getStatus, "RUNNING"));
        logJudge(s.getId(), "RESULT", o.resultCode() + " " + o.passed() + "/" + o.total()
                + " time=" + o.timeUsedMs() + "ms" + (n == 0 ? " DISCARDED(watchdog requeued)" : ""));
        if (n == 0) {
            log.warn("submission {} already requeued by watchdog, discard late result {}",
                    s.getId(), o.resultCode());
            return;
        }
        // 实体同步成终态后推送——WS 实时展示；轮询兜底读的就是这条 DONE 记录
        s.setStatus("DONE");
        s.setResultCode(o.resultCode());
        s.setPassedCases(o.passed());
        s.setTotalCases(o.total());
        s.setTimeUsedMs(o.timeUsedMs());
        s.setErrorMsg(o.errorMsg());
        pushService.pushResult(s);
        log.info("submission {} judged DONE/{} ({}/{})",
                s.getId(), o.resultCode(), o.passed(), o.total());
    }

    /** 系统故障通道：RUNNING -> REQUEUE（同样 CAS 守卫），并留 INFRA_ERROR 时间线 */
    private void requeue(Submission s, String event, String detail) {
        JudgeStateMachine.assertTransition(JudgeStateMachine.RUNNING, JudgeStateMachine.REQUEUE);
        submissionMapper.update(null,
                Wrappers.<Submission>lambdaUpdate()
                        .set(Submission::getStatus, "REQUEUE")
                        .eq(Submission::getId, s.getId())
                        .eq(Submission::getStatus, "RUNNING"));
        logJudge(s.getId(), event, detail);
    }

    private ProblemInfo fetchProblem(Long problemId) {
        Result<ProblemInfo> r;
        try {
            r = problemClient.getProblem(problemId);
        } catch (Exception e) {
            throw new JudgeInfraException("Feign 拉题目失败 problem=" + problemId + ": " + e.getMessage(), e);
        }
        if (r == null || r.code != 0 || r.data == null) {
            throw new JudgeInfraException("题目不存在或题库报错 problem=" + problemId);
        }
        return r.data;
    }

    private List<CaseInfo> fetchCases(Long problemId) {
        Result<List<CaseInfo>> r;
        try {
            r = problemClient.getCases(problemId);
        } catch (Exception e) {
            throw new JudgeInfraException("Feign 拉用例失败 problem=" + problemId + ": " + e.getMessage(), e);
        }
        if (r == null || r.code != 0 || r.data == null) {
            throw new JudgeInfraException("用例拉取失败 problem=" + problemId);
        }
        return r.data;
    }

    /** 写评测时间线：日志本身失败绝不影响判分主流程（观测性代码的容错铁律） */
    private void logJudge(Long submissionId, String event, String detail) {
        try {
            JudgeLog jl = new JudgeLog();
            jl.setSubmissionId(submissionId);
            jl.setEvent(event);
            jl.setDetail(detail == null ? event
                    : detail.length() > 500 ? detail.substring(0, 500) : detail);
            jl.setCreatedAt(LocalDateTime.now());
            judgeLogMapper.insert(jl);
        } catch (Exception e) {
            log.warn("judge_log insert failed sub={} event={}", submissionId, event, e);
        }
    }

    /**
     * simulate 模式进程非零退出的崩溃类判定唯一入口：先查 stderr 的 OOM
     * 特征串判 MLE，查不到才判 RE——顺序即语义，OOM 也是运行时异常的一种，
     * 若先按 RE 粗判就会把"超内存"这一独立信号永久淹没在通用崩溃里。
     *
     * <p>近似性声明：本判定建立在 SandboxRunner.simulateJavaCommand 给子
     * JVM 设置的 -Xmx（32~512m 钳制）之上，只能抓到堆溢出；native 内存、
     * 线程栈爆炸抓不到。docker 模式由内核 OOMKill 兜底（SIGKILL 退出码
     * 137，无 stderr 可查），两模式分工见 SandboxRunner 类注释。</p>
     *
     * <p>包内可见：单测直接喂真子进程产出的 stderr 断言判 MLE/判 RE，
     * 不必拖着 Spring/DB 起整机。</p>
     */
    static String crashVerdict(String stderr) {
        return looksLikeOom(stderr) ? "MLE" : "RE";
    }

    /** OOM 特征串判定：OutOfMemoryError 为堆溢出主签名，GC overhead 是其变体（堆没爆但回收烧穿时限） */
    private static boolean looksLikeOom(String stderr) {
        return stderr != null
                && (stderr.contains("OutOfMemoryError") || stderr.contains("GC overhead"));
    }

    /** stderr 摘要截断：留头不留尾（首个报错行通常就在头部） */
    private static String trimMsg(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() > MAX_ERROR_MSG ? t.substring(0, MAX_ERROR_MSG) + "...(截断)" : t;
    }

    /** 判完清工作目录（防磁盘涨）；keep-work-dir=true 时保留现场调试 */
    private void cleanupWorkDir(Path workDir) {
        if (workDir == null || keepWorkDir) {
            return;
        }
        try (var walk = Files.walk(workDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 删不掉留给下次/运维脚本，评测主流程不因此失败
                        }
                    });
        } catch (IOException e) {
            log.warn("cleanup work dir {} failed", workDir, e);
        }
    }

    /** 单任务判定聚合结果：写 t_submission 终态所需的全部字段 */
    private record Outcome(String resultCode, int passed, int total,
                           long timeUsedMs, String errorMsg) {}
}

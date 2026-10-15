package com.minijudge.judge.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱执行器——本项目安全灵魂。两种模式（配置 mini-judge.sandbox.mode）：
 *
 * <p>【docker 模式=生产形态】计划书 7.1 的五层护栏全部落在 buildCommand：
 * 1. --network none      ：防反弹 shell / 偷答案 / 打内网；
 * 2. --memory/--cpus     ：防单任务吃光评测机（超限由内核 OOM kill / 调度限速）；
 * 3. --pids-limit 64     ：防 fork 炸弹拖垮宿主机；
 * 4. --read-only + 只挂  ：防读改宿主文件——只读根文件系统，用例卷 ro，仅 /tmp 可写；
 * 5. -u 1000 + seccomp   ：防容器提权与危险系统调用。
 * 容器内由镜像入口 judge-run.sh 完成"编译+逐用例执行+比对"，宿主侧只解析
 * 一行摘要（协议见 docker/judge-java/judge-run.sh），杜绝大输出回传。</p>
 *
 * <p>【simulate 模式=开发/演示形态，重要声明】直接用本机 JDK 的 javac/java
 * 编译运行用户代码并套用超时。它没有任何容器护栏：代码可读宿主文件、可
 * 起进程、可吃满内存（-Xmx 只是堆上限，防不住 native/多线程），仅限开发
 * 机验证评测链路时使用，生产必须 docker 模式——面试点"护栏在哪一层实现"：
 * 护栏属于操作系统级（namespace/cgroup/seccomp），任何 JVM 内的
 * SecurityManager/超时线程都替代不了，simulate 就是"没有护栏的对照组"。</p>
 *
 * <p>内存超限（MLE）判定的两模式分工：docker 模式由内核 OOMKill 兜底——
 * 容器 --memory 超限进程收 SIGKILL（退出码 137），容器内脚本据此报 MLE；
 * simulate 模式用 -Xmx 近似——评测子 JVM 堆上限取题目 memoryLimitKb 换算
 * MB（下限 32m 防 JVM 自身起不来被冤判，上限 512m 防无 cgroup 兜底时
 * simulate 挤爆评测机），用户代码超堆抛 OutOfMemoryError 非零退出，判读层
 * 先按 stderr 的 OOM 关键字判 MLE、查不到才判 RE（区分顺序见
 * JudgeWorkerPool.crashVerdict）。近似性要诚实：-Xmx 只兜住堆，native 内存
 * 泄漏、线程栈爆炸在 simulate 下抓不到，这正是"对照组"的已知盲区。</p>
 *
 * <p>超时控制（两模式共同思路）：宿主侧 Process.waitFor(timeout) 到点
 * destroyForcibly——simulate 杀的是真进程；docker 模式里 waitFor 等的是宿主端
 * `docker run` 客户端进程，极端情况客户端先挂住而容器还在跑，所以调度层
 * 看门狗（JudgeWatchdog）再兜一层，双保险。</p>
 */
@Slf4j
@Component
public class SandboxRunner {

    /** simulate 运行超时附加的"进程启动宽限"毫秒数：见下方注释 */
    private static final long PROCESS_STARTUP_GRACE_MS = 500;

    /** Java 评测镜像：预装 JDK，内含入口脚本 judge-run.sh（docker/judge-java/） */
    @Value("${mini-judge.sandbox.java-image:judge-java:latest}")
    private String javaImage = "judge-java:latest";

    /** 用例在容器内的挂载根路径（docker 模式）；单机 compose 与宿主共享卷同路径 */
    @Value("${mini-judge.sandbox.cases-root:/data/cases}")
    private String casesRoot = "/data/cases";

    /** docker|simulate；字段带初始值是为了单测直接 new 本类而不走 Spring 注入 */
    @Value("${mini-judge.sandbox.mode:docker}")
    private String mode = "docker";

    /** 判定辅助记录：124 惯例为 timeout 包装退出码；137=SIGKILL(多为 OOM/超时强杀) */
    public record RunResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean killed() { return exitCode == 137; }
    }

    public boolean isSimulate() {
        return "simulate".equalsIgnoreCase(mode);
    }

    /** 包内可见+可注入：单测里直接构造"模拟模式"实例，不依赖 Spring 环境 */
    void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * 在沙箱内执行一次：把用户源码与用例目录挂载进去。
     *
     * @param workDir   该提交专属宿主临时目录（存源码文件），将以 ro 挂到容器 /work
     * @param inputDir  该题用例目录（in 与期望 out 都在内，容器内比对不落宿主）
     */
    public RunResult run(String language, String workDir, String caseDir,
                         int timeLimitMs, int memoryLimitKb) throws IOException, InterruptedException {
        List<String> cmd = buildCommand(language, workDir, caseDir, timeLimitMs, memoryLimitKb);
        log.debug("sandbox exec: {}", cmd);

        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();
        // 超时 = 全部用例时限总和的宿主兜底：用例级超时由容器内 timeout 命令负责，
        // 宿主这层只防"docker 客户端挂死"，所以给 5 倍时限 + 30s 的宽裕量
        boolean finished = p.waitFor(5L * timeLimitMs / 1000 + 30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new RunResult(124, "", "time limit exceeded", true);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new RunResult(p.exitValue(), out, err, false);
    }

    /** 命令构建独立成方法：单测直接断言参数包含全部护栏开关（安全回归测试思路） */
    List<String> buildCommand(String language, String workDir, String caseDir,
                              int timeLimitMs, int memoryLimitKb) {
        String image = "CPP17".equals(language) ? "judge-cpp:latest" : javaImage;
        long memMb = Math.max(64, memoryLimitKb / 1024);
        return List.of(
                "docker", "run", "--rm",
                "--network", "none",                       // 护栏1
                "--memory", memMb + "m",                   // 护栏2
                "--cpus", "1",                             // 护栏2
                "--pids-limit", "64",                      // 护栏3
                "--read-only",                             // 护栏4
                "--tmpfs", "/tmp:rw,size=32m",             // 护栏4 例外：唯一可写区
                "--security-opt", "seccomp=profile.json",  // 护栏5（镜像内提供 profile 文件）
                "-u", "1000:1000",                         // 护栏5 降权
                "-v", workDir + ":/work:ro",               // 源码只读进容器
                "-v", caseDir + ":" + casesRoot + ":ro",   // 用例只读
                image,
                "/bin/sh", "/usr/local/bin/judge-run.sh",  // 镜像内固定入口
                String.valueOf(timeLimitMs));
    }

    // ===================== simulate 模式：本机 JDK 直跑 =====================

    /**
     * 定位与当前 JVM 同源的编译/运行工具：System.getProperty("java.home") 指向
     * 启动本进程（或本测试）的 JDK/JRE 目录，Windows 下带 .exe 后缀。
     * 用 java.home 而不是 PATH 找 javac：保证"评测用的 JDK == 服务运行的 JDK"，
     * PATH 被用户环境污染时结果仍稳定可复现。
     */
    static Path jdkTool(String name) {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? name + ".exe" : name;
        return Paths.get(System.getProperty("java.home"), "bin", exe);
    }

    /**
     * simulate 编译：javac -encoding UTF-8 把 workDir/Main.java 编到 classesDir。
     * 输出流重定向到文件再回读——若挂在管道上等 waitFor，大段编译错误
     * 撑满 OS 管道缓冲（约 64KB）会让 javac 写阻塞、waitFor 假超时，写文件天然免疫。
     */
    public RunResult simulateCompile(Path workDir, Path sourceFile, Path classesDir,
                                     int timeoutMs) throws IOException, InterruptedException {
        Files.createDirectories(classesDir);
        Path outLog = workDir.resolve("compile_stdout.log");
        Path errLog = workDir.resolve("compile_stderr.log");
        List<String> cmd = List.of(
                jdkTool("javac").toString(),
                "-encoding", "UTF-8",          // 源码按 UTF-8 读，防中文注释在 GBK 平台误报
                // GUI 测试修复：javac 的诊断信息按"本地化+平台编码"输出——中文 Windows 上
                // 是 GBK 编码的中文报错，前端按 UTF-8 读全成乱码。-J 参数传给 javac 的 JVM：
                // 强制英文语言环境，诊断退化为纯 ASCII，从根上消灭编码歧义（评测机通用做法，
                // docker 模式下 LANG=C 同理）。
                "-J-Duser.language=en", "-J-Duser.country=US",
                "-d", classesDir.toString(),
                sourceFile.toString());
        log.debug("simulate compile: {}", cmd);
        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectOutput(outLog.toFile())
                .redirectError(errLog.toFile())
                .start();
        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            forceKillBounded(p);
            return new RunResult(124, "", "compile timeout", true);
        }
        return new RunResult(p.exitValue(), readUtf8(outLog), readUtf8(errLog), false);
    }

    /**
     * simulate 执行单个用例：本机 java 运行 classesDir 里的 mainClass，
     * stdin 重定向用例输入文件，stdout/stderr 落盘回读，wall-clock 超时强杀。
     *
     * <p>两个工程细节：
     * 1. -Dfile.encoding=UTF-8：Windows 默认 GBK，用户 println 的中文经 GBK 落盘
     *    会和 UTF-8 期望输出比对失败——统一 UTF-8 消除平台差异；
     * 2. 超时=用户时限+启动宽限 500ms：simulate 的 wall-clock 含 JVM 启动开销，
     *    不加宽限会系统性冤判 TLE；生产 docker 在容器内用 timeout 命令只对
     *    用户进程计时，无此问题（simulate 仅演示用，差距可接受）。</p>
     */
    public RunResult simulateRun(Path workDir, Path classesDir, String mainClass,
                                 Path stdinFile, int timeLimitMs, int memoryLimitKb)
            throws IOException, InterruptedException {
        Path outLog = workDir.resolve("case_stdout.log");
        Path errLog = workDir.resolve("case_stderr.log");
        List<String> cmd = simulateJavaCommand(classesDir, mainClass, memoryLimitKb);
        log.debug("simulate run: {} < {}", cmd, stdinFile);
        Process p = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectInput(stdinFile.toFile())
                .redirectOutput(outLog.toFile())
                .redirectError(errLog.toFile())
                .start();
        boolean finished = p.waitFor(timeLimitMs + PROCESS_STARTUP_GRACE_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            forceKillBounded(p);
            return new RunResult(124, readUtf8(outLog), "time limit exceeded", true);
        }
        return new RunResult(p.exitValue(), readUtf8(outLog), readUtf8(errLog), false);
    }

    /**
     * simulate 运行的 java 命令构建——独立成方法是为了单测能纯逻辑断言 -Xmx
     * 钳制区间（同 docker buildCommand 的"参数即护栏"测试思路）。
     *
     * <p>内存参数取舍：堆上限取题目 memoryLimitKb 换算 MB，钳制在 32~512m——
     * 下限 32m：再低 JVM 自身（类加载/JIT）都起不来，会把任何代码冤判成 MLE；
     * 上限 512m：simulate 没有容器 cgroup 兜底，题目误配一个超大内存限制时，
     * 放行等于允许多个并发子 JVM 挤爆评测机（worker 池隔离性铁律）。
     * 注意 -Xmx 只管堆，管不住 native/线程栈——它不能替代 docker 的 cgroup
     * 内存限制，只是让 simulate 具备"超内存抛 OutOfMemoryError"这条可判定的
     * 近似链路（判读见 JudgeWorkerPool.crashVerdict：OOM 关键字先于 RE）。</p>
     */
    static List<String> simulateJavaCommand(Path classesDir, String mainClass, int memoryLimitKb) {
        long memMb = Math.min(512, Math.max(32, memoryLimitKb / 1024));
        return List.of(
                jdkTool("java").toString(),
                "-Dfile.encoding=UTF-8",
                "-Xmx" + memMb + "m",
                "-cp", classesDir.toString(),
                mainClass);
    }

    /**
     * 强杀子进程并"有界"确认死亡。绝不用 destroyForcibly().waitFor() 无限等：
     * Windows 杀软实时扫描可能把进程终止拖到分钟级（本机实测挂 435 秒），
     * 无限等会让 worker 线程跟着陪葬，违背"单任务死循环不拖累他人"的隔离性
     * 铁律（计划书 3.2）——等不到也继续走，孤儿进程由看门狗层再兜。
     */
    private static void forceKillBounded(Process p) throws InterruptedException {
        p.destroyForcibly();
        // 给 2 秒正常收尾窗口；超时就放弃确认，不阻塞评测 worker
        p.waitFor(2, TimeUnit.SECONDS);
    }

    /** 输出日志读取重试次数/间隔：总预算约 3 秒，覆盖本机实测的杀软文件锁窗口 */
    private static final int READ_RETRY_TIMES = 20;
    private static final long READ_RETRY_INTERVAL_MS = 150;

    /**
     * 读子进程输出日志文件。两层工程细节都是本机实测换来的：
     *
     * <p>1. 宽容解码而非 Files.readString：javac 在中文 Windows 上用本地
     * 编码（GBK）输出诊断信息，严格 UTF-8 解码撞见非法字节会抛
     * MalformedInputException("Input length = 1")，CE 报错会被整段吞成空串。
     * 改 readAllBytes + new String(bytes, UTF_8)：非法序列替换成 U+FFFD，
     * 永不抛——中文诊断虽花屏但行数/位置仍在，且 RE/MLE 判定依赖的
     * OutOfMemoryError 等关键字是 ASCII，不受影响。用户代码输出无此问题：
     * simulateRun 给子 JVM 统一了 -Dfile.encoding=UTF-8。</p>
     *
     * <p>2. 对 IOException 重试（杀软短暂锁文件/未落盘完成），读成功即返回
     * ——空串也返回（成功编译的 stderr 本来就是空的，不能傻等）；预算耗尽
     * 仍失败返回空串不抛：判读逻辑绝不为一条日志崩掉，比对/判定环节自会
     * 兜住空输出。</p>
     */
    private static String readUtf8(Path file) {
        IOException lastError = null;
        for (int attempt = 0; attempt < READ_RETRY_TIMES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(READ_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                lastError = e;     // 文件被杀软锁定或还没创建，退避后重试
            }
        }
        if (lastError != null) {
            log.warn("read sandbox log failed: {} ({})", file, lastError.getMessage());
        }
        return "";
    }
}

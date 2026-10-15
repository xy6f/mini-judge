package com.minijudge.judge.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱"五层护栏"安全回归测试（面试王牌章节的自动化形态）：
 * 护栏是命令行参数，一旦被手滑删掉，功能测试全绿也发现不了——所以直接对
 * buildCommand 的产物逐层断言。以后任何人改这行命令，CI 会指着他说：
 * "--network none 去哪了？那是防反弹 shell 的。"
 */
class SandboxRunnerBuildCommandTest {

    /** 取字段默认值（javaImage/casesRoot）即可独立 new，不经 Spring 上下文 */
    private final SandboxRunner runner = new SandboxRunner();

    @Test
    @DisplayName("docker 命令必须齐五层护栏：网络隔离/资源上限/pids/只读+tmpfs/降权+seccomp")
    void allFiveGuardrailsPresent() {
        List<String> cmd = runner.buildCommand("JAVA17", "/host/work", "/host/cases", 2000, 262144);
        String joined = String.join(" ", cmd);

        assertTrue(cmd.contains("docker") && cmd.contains("run") && cmd.contains("--rm"));
        // 护栏1：无网络——防反弹 shell/偷答案/打内网
        assertTrue(joined.contains("--network none"));
        // 护栏2：内存与 CPU——256MB 与 1 核；memKB/1024=MB 的换算也要对
        assertTrue(joined.contains("--memory 256m"));
        assertTrue(joined.contains("--cpus 1"));
        // 护栏3：进程数上限——防 fork 炸弹
        assertTrue(joined.contains("--pids-limit 64"));
        // 护栏4：只读根文件系统 + 唯一可写区 /tmp 且限 32m 容量
        assertTrue(cmd.contains("--read-only"));
        assertTrue(joined.contains("--tmpfs /tmp:rw,size=32m"));
        // 护栏5：非 root 降权 + seccomp 系统调用白名单
        assertTrue(joined.contains("-u 1000:1000"));
        assertTrue(joined.contains("--security-opt seccomp=profile.json"));
        // 卷挂载必须是 ro：源码与用例只进不改
        assertTrue(joined.contains("-v /host/work:/work:ro"));
        assertTrue(joined.contains("-v /host/cases:/data/cases:ro"));
        // 用户时限必须传给容器内入口脚本（用例级超时的真正执行者）
        assertEquals("2000", cmd.get(cmd.size() - 1));
    }

    @Test
    @DisplayName("内存下限钳制：极小 memoryLimitKb 也要抬到 64m（JVM 自己都起不来的配置等于自杀）")
    void memoryFloorClamped() {
        List<String> cmd = runner.buildCommand("JAVA17", "/w", "/c", 1000, 32);
        String joined = String.join(" ", cmd);
        assertTrue(joined.contains("--memory 64m"));
    }

    @Test
    @DisplayName("语言路由：JAVA17 用 judge-java 镜像，CPP17 换 judge-cpp")
    void imagePerLanguage() {
        List<String> java = runner.buildCommand("JAVA17", "/w", "/c", 2000, 262144);
        List<String> cpp = runner.buildCommand("CPP17", "/w", "/c", 2000, 262144);
        assertTrue(java.contains("judge-java:latest"));
        assertTrue(cpp.contains("judge-cpp:latest"));
    }

    @Test
    @DisplayName("simulate 的 -Xmx 钳制：memoryLimitKb 换算 MB 后限制在 32~512m 区间")
    void simulateXmxClampedTo32To512() {
        Path dummy = Path.of("/tmp/classes");
        // 下限：题目误配 16MB（甚至 0/负数）也要抬到 32m，否则 JVM 自身起不来被冤判 MLE
        assertTrue(SandboxRunner.simulateJavaCommand(dummy, "Main", 16 * 1024).contains("-Xmx32m"));
        assertTrue(SandboxRunner.simulateJavaCommand(dummy, "Main", 0).contains("-Xmx32m"));
        // 区间内直通：256MB 题配 -Xmx256m（演示 A+B 题的默认值，正常题不受钳制影响）
        assertTrue(SandboxRunner.simulateJavaCommand(dummy, "Main", 262144).contains("-Xmx256m"));
        // 上限：1GB 配置压到 512m——simulate 无 cgroup 兜底，防并发子 JVM 挤爆评测机
        assertTrue(SandboxRunner.simulateJavaCommand(dummy, "Main", 1024 * 1024).contains("-Xmx512m"));
    }

    @Test
    @DisplayName("MLE/RE 判读顺序：含 OutOfMemoryError 判 MLE，普通崩溃判 RE")
    void crashVerdictOomBeforeRe() {
        assertEquals("MLE", JudgeWorkerPool.crashVerdict(
                "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"));
        assertEquals("MLE", JudgeWorkerPool.crashVerdict(
                "java.lang.OutOfMemoryError: GC overhead limit exceeded"));
        // 不含 OOM 关键字的普通运行时异常不得被抢判 MLE——先查 OOM 再判 RE 的"先"字有测试背书
        assertEquals("RE", JudgeWorkerPool.crashVerdict(
                "Exception in thread \"main\" java.lang.NullPointerException"));
        assertEquals("RE", JudgeWorkerPool.crashVerdict(null));
    }
}

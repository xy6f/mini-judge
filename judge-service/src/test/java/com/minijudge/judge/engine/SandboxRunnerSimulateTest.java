package com.minijudge.judge.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * simulate 模式端到端单测：在测试 JVM 里真起 javac/java 子进程，验证
 * "合法 Java 判 AC、死循环判 TLE、抛异常判 RE、超内存判 MLE、坏语法判 CE"
 * 五条真链路（MLE 链路= -Xmx 钳制 + OutOfMemoryError 特征串判读，
 * 判读优先级"先 OOM 后 RE"的区分线也在本类断言）。
 *
 * <p>工程要点：
 * 1. 工具定位用 System.getProperty("java.home")（SandboxRunner.jdkTool），
 *    保证评测 JDK 与跑测试的 JDK 同源，不依赖也不污染 PATH（Windows 上
 *    where javac 可能被别的安装劫持，测试要可复现）；
 * 2. 纯本机进程，不碰 Docker/Nacos/网络——符合"mvn test 禁外部依赖"红线；
 * 3. TLE 用例给 1500ms 时限 + 500ms 启动宽限，整条断言 3s 内出结果，
 *    测试不会因为 JVM 启动慢而假阳性，也不会真等满进程寿命。</p>
 */
class SandboxRunnerSimulateTest {

    /** 每题 256MB 上限：对测试子进程绰绰有余 */
    private static final int MEM_KB = 262144;

    /**
     * 自建临时工作目录（不用 @TempDir）：JUnit 的 @TempDir 在 Windows 上会因
     * 杀软瞬时锁住子进程日志文件而"删不掉 -> 判测试 ERROR"（本机实测），
     * 与断言无关的环境性误报。这里改为自己建、自己删且删除失败静默忽略——
     * 测试正确性只取决于断言，不取决于清扫。
     */
    Path workDir;

    @BeforeEach
    void createWorkDir() throws IOException {
        workDir = Files.createTempDirectory("judge-sim-test");
    }

    @AfterEach
    void removeWorkDir() {
        try (var walk = Files.walk(workDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清扫尽力而为，失败不判错（见字段注释）
                        }
                    });
        } catch (IOException ignored) {
            // 同上
        }
    }

    @Test
    @DisplayName("合法两数求和程序：编译过、按 stdin 跑出正确 stdout（AC 链路）")
    void validProgramCompilesAndRuns() throws IOException, InterruptedException {
        writeSource("""
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println(sc.nextInt() + sc.nextInt());
                    }
                }
                """);
        SandboxRunner runner = new SandboxRunner();

        SandboxRunner.RunResult compile = runner.simulateCompile(
                workDir, src(), workDir.resolve("out"), 15_000);
        assertEquals(0, compile.exitCode(), "javac 应成功: " + compile.stderr());
        assertFalse(compile.timedOut());

        Path in = workDir.resolve("case1.in");
        Files.writeString(in, "1 2\n", StandardCharsets.UTF_8);
        SandboxRunner.RunResult r = runner.simulateRun(
                workDir, workDir.resolve("out"), "Main", in, 2000, MEM_KB);

        assertFalse(r.timedOut());
        assertEquals(0, r.exitCode());
        // 交给比对器复核：simulateRun 的 stdout 与期望"3"应判 AC
        assertTrue(OutputComparator.matches(r.stdout(), "3\n"));
    }

    @Test
    @DisplayName("死循环程序：wall-clock 超时被强杀并标记 timedOut（TLE 判定基础）")
    void infiniteLoopTimesOut() throws IOException, InterruptedException {
        writeSource("""
                public class Main {
                    public static void main(String[] args) {
                        while (true) { }
                    }
                }
                """);
        SandboxRunner runner = new SandboxRunner();
        assertEquals(0, runner.simulateCompile(workDir, src(), workDir.resolve("out"), 15_000).exitCode());

        Path in = workDir.resolve("empty.in");
        Files.writeString(in, "", StandardCharsets.UTF_8);

        long t0 = System.currentTimeMillis();
        SandboxRunner.RunResult r = runner.simulateRun(
                workDir, workDir.resolve("out"), "Main", in, 1500, MEM_KB);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(r.timedOut(), "死循环必须在时限+宽限内被强杀");
        // 判定下限=时限本身；上限给 10s 防 CI 机器抖动导致的假失败断言
        assertTrue(elapsed >= 1400, "不该比时限还早就判超时: " + elapsed);
        assertTrue(elapsed < 10_000, "强杀不及时: " + elapsed);
    }

    @Test
    @DisplayName("运行期异常：非零退出码透传，stderr 留 RE 现场（RE 判定基础）")
    void runtimeExceptionExitsNonZero() throws IOException, InterruptedException {
        writeSource("""
                public class Main {
                    public static void main(String[] args) {
                        throw new RuntimeException("boom");
                    }
                }
                """);
        SandboxRunner runner = new SandboxRunner();
        runner.simulateCompile(workDir, src(), workDir.resolve("out"), 15_000);
        Path in = workDir.resolve("empty.in");
        Files.writeString(in, "", StandardCharsets.UTF_8);

        SandboxRunner.RunResult r = runner.simulateRun(
                workDir, workDir.resolve("out"), "Main", in, 2000, MEM_KB);
        assertFalse(r.timedOut());
        assertTrue(r.exitCode() != 0);
        assertTrue(r.stderr().contains("boom"));
        // 与 MLE 的区分线：普通崩溃不含 OOM 关键字，判读必须落到 RE
        assertEquals("RE", JudgeWorkerPool.crashVerdict(r.stderr()));
    }

    @Test
    @DisplayName("超内存程序：-Xmx32m 下分配 256MB int[] 抛 OutOfMemoryError，判 MLE（非 RE）")
    void heapBlowupUnder32mXmxIsMle() throws IOException, InterruptedException {
        writeSource("""
                public class Main {
                    // 静态字段持有引用防 JIT 把"死分配"优化掉，确保真触发堆溢出
                    static int[] bomb;
                    public static void main(String[] args) {
                        bomb = new int[64 * 1024 * 1024]; // 256MB，远超 -Xmx32m
                        System.out.println(bomb.length);
                    }
                }
                """);
        SandboxRunner runner = new SandboxRunner();
        assertEquals(0, runner.simulateCompile(workDir, src(), workDir.resolve("out"), 15_000).exitCode());
        Path in = workDir.resolve("empty.in");
        Files.writeString(in, "", StandardCharsets.UTF_8);

        // 题目内存配 16MB：低于下限，-Xmx 应被钳到 32m——先用纯命令断言钳制，再真跑
        assertTrue(SandboxRunner.simulateJavaCommand(
                        workDir.resolve("out"), "Main", 16 * 1024).contains("-Xmx32m"),
                "低于下限 32m 的 memoryLimitKb 必须被钳到 -Xmx32m");
        // 时限给 8s 宽裕：32m 小堆 JVM 在慢机上启动可能偏久，本测试断言的是 MLE 不是 TLE
        SandboxRunner.RunResult r = runner.simulateRun(
                workDir, workDir.resolve("out"), "Main", in, 8000, 16 * 1024);

        assertFalse(r.timedOut(), "不应误判超时: " + r.stderr());
        assertTrue(r.exitCode() != 0, "堆溢出必须非零退出");
        assertTrue(r.stderr().contains("OutOfMemoryError"), "stderr=" + r.stderr());
        // 判读链收口：stderr 含 OutOfMemoryError 且退出码非 0 -> MLE，而非笼统 RE
        assertEquals("MLE", JudgeWorkerPool.crashVerdict(r.stderr()));
    }

    @Test
    @DisplayName("语法错误源码：javac 非零退出且 stderr 有报错（CE 判定基础）")
    void brokenSourceFailsCompile() throws IOException, InterruptedException {
        writeSource("public class Main { this is not java }");
        SandboxRunner.RunResult compile = new SandboxRunner()
                .simulateCompile(workDir, src(), workDir.resolve("out"), 15_000);
        assertFalse(compile.timedOut());
        assertTrue(compile.exitCode() != 0);
        assertFalse(compile.stderr().isEmpty(), "stderr=" + compile.stderr());
    }

    private Path src() {
        return workDir.resolve("Main.java");
    }

    private void writeSource(String code) throws IOException {
        Files.writeString(src(), code, StandardCharsets.UTF_8);
    }
}

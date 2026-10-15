# judge-java 评测镜像（计划书 7.1"每种语言一个镜像"之 Java）
#
# 镜像职责很薄：只提供"编译 + 运行 Java 的干净环境 + 判分入口脚本"。
# 所有资源/网络/文件系统护栏都在宿主启动参数里（SandboxRunner.buildCommand），
# 镜像不参与安全决策——安全边界外置，镜像被篡改也无法越过 cgroup/namespace。
#
# 构建（在 Linux 评测机或 Docker Desktop 里）：
#   docker build -t judge-java:latest docker/judge-java
# 命名必须对齐 judge-service 配置 mini-judge.sandbox.java-image=judge-java:latest
#
# 注意：基础镜像选 -jdk 而非 -jre——用户源码要先过 javac 编译，jre 没有编译器。
FROM eclipse-temurin:17-jdk-jammy

# 约定挂载点（运行时用 -v 覆盖）：
#   /work        宿主提交工作目录，只读挂载（内含 Main.java）
#   /data/cases  该题用例目录，只读挂载（N.in / N.out）
# /tmp 由宿主 --tmpfs 提供可写区（--read-only 下唯一可写路径），这里不预建
RUN mkdir -p /work /data/cases && \
    # 校验镜像内有 javac：入口脚本直接调用，缺了要在这里就炸而不是评测时才发现
    javac -version && java -version

# 判分入口：容器内完成"编译+逐用例执行+比对"，stdout 只输出一行摘要协议
# （协议定义见脚本头注释，宿主侧正则 DOCKER_SUMMARY 与之严格对齐）
COPY judge-run.sh /usr/local/bin/judge-run.sh
RUN chmod 0755 /usr/local/bin/judge-run.sh

# 不设 USER：运行期由宿主 `-u 1000:1000` 降权（计划书护栏5）。
# 写死 USER 反而会让"以哪个 uid 运行"这个安全决策固化在镜像里，
# 宿主参数是唯一事实源（single source of truth）。
# 也不设 ENTRYPOINT/CMD：宿主显式给 `/bin/sh /usr/local/bin/judge-run.sh <时限ms>`，
# 参数必须能被 buildCommand 完全控制。

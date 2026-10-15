#!/bin/sh
# =====================================================================
# judge-java 镜像判分入口脚本（容器内执行，计划书 7.1/第 8 章流程）
#
# 摘要协议（与宿主 JudgeWorkerPool.DOCKER_SUMMARY 正则严格对齐，改一处必须改两处）：
#   stdout 最后一行：VERDICT=<AC|WA|TLE|MLE|RE|CE> PASSED=<通过数> TOTAL=<用例总数> TIME=<最大单用例耗时ms>
# 除这一行外不得向 stdout 写任何东西（用户输出/编译报错全部导向文件），
# 宿主只消费这一行——大输出不出容器，杜绝管道边界问题（计划书 7.1）。
#
# 系统故障（挂载缺失/用例不全/tmp 不可写）不产 VERDICT 行：宿主解析失败
# 即抛 JudgeInfraException -> REQUEUE 重跑，评测器出错绝不冤枉用户。
#
# 判定规则（与 simulate 模式的 Java 侧逻辑一致，两模式互为对照组）：
#   CE   javac 非零退出（编译失败短路，不跑用例）
#   TLE  timeout 包装退出码 124（用例级超时，容器内计时只算用户进程）
#   MLE  退出码 137（cgroup OOM killer SIGKILL——内存护栏由宿主 --memory 提供，
#        这里只是识别其特征；这是 docker 模式相对 simulate 的真护栏优势）
#   RE   其他非零退出（运行期异常）
#   WA   正常退出但输出比对不等
#   AC   全部用例通过
# 比对规则与 OutputComparator 对齐：逐行去行尾空白、忽略文件末尾空行。
#
# 失败短路：任一非 AC 立即停止并输出摘要（省算力，等价宿主 fail-fast=true；
# 若将来要 per-case 详情进摘要，扩展协议而非改判定语义）。
#
# 挂载约定（由宿主 SandboxRunner.buildCommand 保证）：
#   /work/Main.java   用户源码（只读）
#   /data/cases/*.in  用例输入；/data/cases/*.out 期望输出（只读）
#   /tmp              唯一可写区（--tmpfs 32m），所有中间产物写这里
# $1 = 单用例 CPU 时限（毫秒），来自题目 t_problem.time_limit_ms
# =====================================================================
set -u

TIME_MS="${1:-2000}"
BUILD=/tmp/jbuild
SRC=/work/Main.java
CASES=/data/cases

# timeout 参数用小数秒（coreutils 支持），按毫秒精确换算，别把 1500ms 截成 1s
TIME_S=$(awk "BEGIN{printf \"%.3f\", ${TIME_MS}/1000}")

total() {
    # 输出一行摘要协议后退出；$1=verdict $2=passed $3=total $4=timeMs
    echo "VERDICT=$1 PASSED=$2 TOTAL=$3 TIME=$4"
    exit 0
}

# 系统故障通道：绝不输出 VERDICT 行，只写 stderr 后非零退出——
# 宿主解析不到摘要会抛 JudgeInfraException 置 REQUEUE 重跑（判分幂等），
# 这是"评测器自身出错绝不冤枉判用户 WA/RE"公正性铁律在脚本侧的落法。
infra_fail() {
    echo "judge-run infra error: $1" >&2
    exit 90
}

# ---- 1. 用例清点（sort -V 按 1,2,10 版本号序，字典序会把 10 排在 2 前）----
[ -f "$SRC" ] || infra_fail "missing $SRC (work volume not mounted?)"
CASE_LIST=$(find "$CASES" -name '*.in' 2>/dev/null | sort -V)
TOTAL=$(printf '%s\n' "$CASE_LIST" | grep -c '' || true)
[ "$TOTAL" -gt 0 ] || infra_fail "no .in case under $CASES"

# ---- 2. 编译（CE 短路）----
mkdir -p "$BUILD" || infra_fail "cannot create $BUILD (/tmp not writable?)"
# 编译时限独立放宽到 max(3*运行时限, 15s)：恶意源码可让 javac 高负载，
# 但编译比运行慢是常态，卡太死会冤杀正常大段代码
COMPILE_S=$(awk "BEGIN{s=${TIME_MS}/1000*3; if(s<15) s=15; printf \"%.3f\", s}")
timeout -s KILL "$COMPILE_S" javac -encoding UTF-8 -d "$BUILD" "$SRC" \
    > /tmp/compile.log 2>&1
if [ $? -ne 0 ]; then
    # 编译报错细节留在容器内 /tmp/compile.log：MVP 摘要协议不含 stderr，
    # 用户看 CE 原文的演进方式是摘要协议加字段（改协议要同步改宿主）
    total "CE" 0 "$TOTAL" 0
fi

# ---- 3. 逐用例执行 + 比对 ----
PASSED=0
MAX_T=0
for IN in $CASE_LIST; do
    OUT="${IN%.in}.out"
    [ -f "$OUT" ] || infra_fail "case $IN has no expected $OUT"
    T0=$(date +%s%N)
    # -Dfile.encoding=UTF-8 与宿主 simulate 模式一致，消除平台编码差异
    timeout -s KILL "$TIME_S" java -Dfile.encoding=UTF-8 -cp "$BUILD" Main \
        < "$IN" > /tmp/actual.out 2>/tmp/actual.err
    CODE=$?
    T=$(( ( $(date +%s%N) - T0 ) / 1000000 ))
    [ "$T" -gt "$MAX_T" ] && MAX_T=$T

    if [ "$CODE" -eq 124 ]; then total "TLE" "$PASSED" "$TOTAL" "$MAX_T"; fi
    if [ "$CODE" -eq 137 ]; then total "MLE" "$PASSED" "$TOTAL" "$MAX_T"; fi
    if [ "$CODE" -ne 0 ];   then total "RE"  "$PASSED" "$TOTAL" "$MAX_T"; fi

    # 归一化后比对：sed 去每行行尾空白（含 \r），awk 丢末尾空行且保留中间空行
    sed -e 's/[[:space:]]*$//' /tmp/actual.out | \
        awk 'NF==0{blank=blank ORS; next}{printf "%s", blank; blank=""; print}' \
        > /tmp/a.n 2>/dev/null
    sed -e 's/[[:space:]]*$//' "$OUT" | \
        awk 'NF==0{blank=blank ORS; next}{printf "%s", blank; blank=""; print}' \
        > /tmp/e.n 2>/dev/null
    cmp -s /tmp/a.n /tmp/e.n || total "WA" "$PASSED" "$TOTAL" "$MAX_T"

    PASSED=$((PASSED + 1))
done

total "AC" "$PASSED" "$TOTAL" "$MAX_T"

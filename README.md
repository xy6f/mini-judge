> Spring Cloud 2023 微服务（gateway + user/problem/judge 三服务）｜ JDK 17 ｜ Vue 3 前端（前后端分离）

**设计目标**：市面上的 OJ 实现大多停留在"题库 CRUD"，本项目的重心是**评测引擎**——如何安全、公正、不阻塞地在服务器上运行完全不可信的陌生人代码。

**功能速览**：提交→DB 队列 CAS 领取→worker 池（信号量=核数）→沙箱执行（docker 五层护栏 / simulate 本机开发模式）→逐用例判分 AC/WA/TLE/MLE/RE/CE→WebSocket 实时推送；RBAC 收口（rejudge/建题需 ADMIN）；Watchdog 孤儿恢复；`/api/judge/status` 监控指标供运维消费。

**快速开始**（local profile 无需 Nacos/Docker）：

```bash
# 后端四服务（依次，端口 8101/8102/8103/8085）
export SPRING_DATASOURCE_PASSWORD=your-mysql-password
for m in user-service problem-service judge-service gateway; do
  mvn -s settings.xml -f $m/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
done
# 前端
cd frontend && npm install && npm run dev   # http://localhost:5174
```

**设计要点索引**：沙箱五层护栏→`SandboxRunner.buildCommand`；并发模型→`JudgeWorkerPool`（信号量/异常分层/finally 归还）；队列选型→`SubmissionMapper`（CAS 领取，DB 队列→MQ 演进）；可靠性→`JudgeDispatcher.recoverOrphans`（幂等重判）；RBAC→`SubmissionService.isAdmin`（fail-closed）。

---

# mini-judge 在线代码评测平台（Spring Cloud 多模块）

> 详细设计见《项目计划书.md》。本 README 覆盖三件事：**本机零依赖冒烟（local
> profile）**、**docker 沙箱形态启动步骤**、**压测方法**。

```
gateway(8080) ──> user-service(8101)
              ├─> problem-service(8102)   # 用例落盘 cases/{problemId}/
              └─> judge-service(8103)     # 核心：队列+worker 池+沙箱评测
judge-common：Result/JwtSupport 公共件
```

## 0. 前置条件

- JDK 17（`java -version` 可确认；simulate 评测直接复用该 JDK）
- 本机 MySQL 8：root 密码见各服务 application.yml（`mini_judge` 库需已存在，
  表由各服务 `schema.sql` 启动时自动创建）
- **local 冒烟不需要 Nacos、不需要 Docker**（这正是 local profile 的意义）

所有 mvn 命令统一形态（Windows Git Bash，settings 指本地仓库镜像配置）：

```bash
MVN="<你的 maven wrapper 路径>/mvn"
"$MVN" -s /e/software/java-internship/maven-settings.xml \
        -f /e/software/java-internship/02-online-code-judge/pom.xml <goals>
```

## 1. local profile 冒烟（无 Nacos / 无 Docker 全链路）

### 1.1 原理一句话

每个服务的 `src/main/resources/application-local.yml` 里做了三件事：
`spring.cloud.nacos.discovery.enabled=false` + `spring.cloud.discovery.enabled=false`
关掉注册中心装配；judge-service 的 Feign `url` 占位符填 `http://localhost:8102`
直连题库；网关路由 `uri` 从 `lb://服务名` 换成直写 `http://localhost:810x`；
judge-service 沙箱切 `simulate` 模式（本机 JDK 直跑，**无安全护栏，仅限开发演示**）。

### 1.2 启动顺序（四个终端，或 & 后台）

```bash
cd /e/software/java-internship/02-online-code-judge
"$MVN" -DskipTests package            # 产出 4 个 fat jar
java -jar user-service/target/user-service-0.1.0.jar    --spring.profiles.active=local
java -jar problem-service/target/problem-service-0.1.0.jar --spring.profiles.active=local
java -jar judge-service/target/judge-service-0.1.0.jar  --spring.profiles.active=local
java -jar gateway/target/gateway-0.1.0.jar              --spring.profiles.active=local
```

注意：problem/judge 的相对路径（cases、work）按各自进程工作目录解析，
请在项目根目录启动保证 judge 能读到 problem-service/cases 的**绝对路径**
（用例路径是 problem-service 落盘时写进数据库的绝对路径，同机天然可读）。

### 1.3 冒烟脚本（每步都有 curl，响应示例见注释）

```bash
GW=http://localhost:8080

# 1) 注册 + 登录（网关 /api/auth/** 白名单，无需令牌）
curl -s -XPOST $GW/api/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"pass1234","nickname":"Alice"}'
TOKEN=$(curl -s -XPOST $GW/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"pass1234"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 2) 建题（A+B 题，时限 2000ms）→ {"code":0,...,"data":{"id":1}}
curl -s -XPOST $GW/api/problems -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"title":"A+B","difficulty":"EASY","timeLimitMs":2000,"description":"输入两个整数，输出它们的和"}'
PID=1   # 用上一步返回的 id

# 3) 传用例（JSON body 内 base64；生产应走对象存储，见 ProblemAdminController 注释）
#    case1: 1 2 -> 3   case2: 100 200 -> 300
curl -s -XPOST $GW/api/problems/$PID/cases -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"cases":[{"inputBase64":"MSAyCg==","outputBase64":"Mwo=","sample":true},
                   {"inputBase64":"MTAwIDIwMAo=","outputBase64":"MzAwCg=="}]}'

# 4) 提交 AC 代码 → {"data":{"id":1,"status":"QUEUED","queuePosition":..}}
AC_CODE='import java.util.Scanner; public class Main { public static void main(String[] a){ Scanner s=new Scanner(System.in); System.out.println(s.nextInt()+s.nextInt()); } }'
SID=$(curl -s -XPOST $GW/api/submissions -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d "{\"problemId\":$PID,\"language\":\"JAVA17\",\"code\":\"$AC_CODE\"}" \
     | sed 's/.*"submissionId":\([0-9]*\).*/\1/')

# 5) 轮询到 DONE/AC（simulate 全链路真实 javac/java 执行）
curl -s $GW/api/submissions/$SID -H "Authorization: Bearer $TOKEN"
# 期望：{"status":"DONE","resultCode":"AC","passedCases":2,"totalCases":2,...}

# 6) 提交死循环代码 → 判 TLE
TLE_CODE='public class Main { public static void main(String[] a){ while(true){} } }'
SID2=$(curl -s -XPOST $GW/api/submissions -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d "{\"problemId\":$PID,\"language\":\"JAVA17\",\"code\":\"$TLE_CODE\"}" \
     | sed 's/.*"submissionId":\([0-9]*\).*/\1/')
curl -s $GW/api/submissions/$SID2 -H "Authorization: Bearer $TOKEN"
# 期望：{"status":"DONE","resultCode":"TLE",...}——且这一步不该阻塞第 5 步类请求

# 7) 评测机状态（给项目四 AIOps Agent 消费的指标接口）
curl -s $GW/api/judge/status -H "Authorization: Bearer $TOKEN"

# 8) 网关用例路由拦截演示：经网关拉全量 cases → 403（LocalCaseDenyFilter）
curl -s -o /dev/null -w "%{http_code}\n" $GW/api/problems/$PID/cases \
     -H "Authorization: Bearer $TOKEN"          # 期望 403
# 对照：judge-service 评测走 Feign 内网直连 8102，不受网关拦截影响，仍能判分
```

local 冒烟收尾：`taskkill //IM java.exe //F`（Git Bash 双斜杠防路径转换）。

## 2. docker 沙箱模式（生产形态）

> **Windows 需先启动 Docker Desktop**（Linux 评测机直接可用）。

```bash
# 1) 构建 Java 评测镜像（含 javac + judge-run.sh 判分入口）
docker build -t judge-java:latest docker/judge-java

# 2) 全栈起（mysql/redis/nacos + 四服务）——需先 mvn -DskipTests package
docker compose up -d
```

docker 模式与 local 的差异（`mini-judge.sandbox.mode`，SandboxRunner）：
- 用户代码进容器执行，五层护栏全开：`--network none`（防反弹 shell/偷答案）、
  `--memory/--cpus`（防吃光评测机）、`--pids-limit 64`（防 fork 炸弹）、
  `--read-only`+只读卷（防翻宿主文件）、`-u 1000`+seccomp（防提权/危险调用）。
- 判分在容器内由 `docker/judge-java/judge-run.sh` 完成，宿主只解析一行
  `VERDICT=.. PASSED=.. TOTAL=.. TIME=..` 摘要协议。
- seccomp：`docker/judge-java/seccomp-profile.json` 是示例（默认放行+拉黑），
  生产换最小 syscall 白名单。

**待真机验证清单**（本机没有 Docker，以下全部是"代码就绪但未实跑"项）：
1. `docker build` judge-java 镜像在目标机通过（基础镜像 eclipse-temurin:17-jdk-jammy）。
2. `docker compose up` 全栈起：nacos 命名空间 `mini-judge` 需在控制台建好，
   且服务注册 gRPC 端口 9848 可达。
3. DooD：judge-service 容器内 `docker run` 在 Windows 需换 npipe 源、
   且 work/cases 容器路径需翻译为宿主路径——推荐生产形态 judge-service
   直接跑宿主机（`--spring.profiles.active=默认 + sandbox.mode=docker`），
   其余服务进容器。
4. `SandboxRunner.buildCommand` 里 `seccomp=profile.json` 是相对 CWD 路径，
   需改为可配置绝对路径并挂 profile 文件。
5. cgroup 内存超限 exit 137 → MLE 判定、fork 炸弹被 pids-limit 拦截的实机演示。
6. Windows Docker Desktop 的文件共享（E: 盘路径挂载进容器）首次需在设置里允许。

## 3. 压测方法（计划书 9 周表 W4 验收项）

思路：验证"提交接口本身不因评测慢而变慢"（异步化价值）与并发下无卡死。

```bash
# 20 并发提交同一份 AC 代码（每个后台 curl 一次），统计提交接口耗时
for i in $(seq 1 20); do
  ( curl -s -o /dev/null -w "%{time_total}s\n" -XPOST $GW/api/submissions \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d "{\"problemId\":$PID,\"language\":\"JAVA17\",\"code\":\"$AC_CODE\"}" ) &
done
wait
```

观察点：
1. 20 次提交接口应在几十毫秒级全部返回（秒回 queuePosition=异步入队成功）；
2. `GET /api/judge/status`：queued 冲高→消化归零，workerBusy≤capacity，
   running 数=并发 worker 数，评测串行不互相拖累；
3. 混入死循环提交压测：TLE 任务占用 worker 仅"时限+宽限"，之后释放；
4. simulate 模式本机压测数字只作链路演示；简历数据必须来自 docker 模式
   Linux 评测机（simulate 无护栏且与业务共享本机 CPU）。

## 4. 单元测试

```bash
"$MVN" test    # 18 个用例全绿，纯本机进程，不依赖 docker/nacos/网络
```

覆盖：OutputComparator 归一化比对（7）、JudgeStateMachine 状态流转（4）、
SandboxRunner.buildCommand 五层护栏参数断言（3）、simulate 模式真 javac/java
跑 AC/TLE/RE/CE 四条链路（4）。

# mini-judge 前端（Vue 3 + Vite + Element Plus + Pinia）

评测控制台前端，v2.0 起从原生三件套升级为 Vue 3 工程化栈（纯 JavaScript，无 TypeScript）。
旧原生版完整归档在 [`legacy-native/`](legacy-native/)，仅作历史参考。

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 框架 | Vue 3（Composition API + `<script setup>`） | 纯 JS |
| 构建 | Vite 6 | `@vitejs/plugin-vue` |
| 组件库 | Element Plus 2.x（暗色主题） | 引入 `theme-chalk/dark/css-vars.css`，`<html class="dark">` |
| 状态 | Pinia 3 | `auth`（token/userId 持久化）、`submissions`（轮询状态机 + 代数防竞态）、`logs`（API 流水） |
| 路由 | vue-router 4（hash 模式） | 题库 `/problems`、提交 `/submit`、监控 `/monitor`；hash 模式使 dist 可被任意静态服务器直接托管 |
| 设计 | `src/styles/tokens.css` | OJ 专业风（对标 LeetCode 中文版 + Codeforces）：亮色为主 `#f6f7f8` 底 + 白卡片（1px `#e8e8e8` 边、4px 圆角），保留暗色切换（顶栏月亮/太阳）；难度色严格对齐 LC（Easy `#26b35c` / Medium `#ffb800` / Hard `#ef4646`，10% 浅底文字徽章）；判定徽章按 OJ 惯例（AC 绿底白字、其余灰底彩字、QUEUED 转圈、大徽章 0.6s 缩放入场）；EP 变量桥 `--el-color-primary: #26b35c` |

## 设计说明（对标 LeetCode 中文版 + Codeforces）

- **主题**：亮色为主（LC 经典亮色：页面底 `#f6f7f8`、白卡片 `#ffffff` + 1px `#e8e8e8` 边 + 4px 紧凑圆角），顶栏月亮/太阳按钮保留暗色切换（`html.dark` + localStorage `mj.theme`）。
- **难度色严格对齐 LeetCode**：Easy `#26b35c` / Medium `#ffb800` / Hard `#ef4646`，题目卡与筛选 chips 均用 10% 透明度同色浅底 + 深色字的文字徽章，弃用圆点。
- **判定徽章对齐 OJ 惯例**：AC 绿底白字圆角；WA/TLE/RE/CE 灰底彩字；QUEUED 转圈图标 + 文字；提交结果大徽章带 0.6s 缩放入场动画。
- **代码编辑器区 LeetCode 化**：顶部语言/题目选择条（`card-head` 灰底工具条）+ 1px 分割线 + 代码区等宽 13px / 行高 20px、行号列 `#adb5bd`，AC 示例代码默认填充。
- **监控页 Grafana 仪表语义**：仪表环粗细 12px、数值 28px 等宽、面板标题 12px 大写字母间距 0.5px（panel header 风），面板底 `#ffffff` 边框 `#e0e0e0`（亮色）/`#3f3f3f`（暗色）。
- **导航**：LeetCode 式顶部横向导航——品牌 logo 左侧、题库/提交/监控居中 tab 式（绿色下划线激活态）、用户徽章与暗色切换右侧；≤900px 折为底部 tab。
- **EP 变量桥**：`--el-color-primary: #26b35c`（LeetCode 绿）统一注入亮/暗两套变量。

## 开发

```bash
cd frontend
npm install        # .npmrc 已指向 https://registry.npmmirror.com
npm run dev        # http://127.0.0.1:5174（strictPort，端口不会漂移）
```

前置：后端网关以 local profile 运行在 **8085**（路由转发 user:8101 / problem:8102 / judge:8103）。

开发期所有 `/api/**` 请求由 `vite.config.js` 的 dev proxy 转发到 `http://localhost:8085`：
浏览器发起的是同源请求，`Authorization: Bearer <JWT>` 原样透传，
网关 `JwtGlobalFilter` 解析令牌后**注入/覆盖 `X-User-Id`** 再路由到下游服务——
这就是网关注入 X-User-Id 的正规姿势，前端不需要（也不应）自己声明可信身份。

## 构建与预览

```bash
npm run build      # 产物输出到 dist/
npm run preview    # 本地预览 dist（可选）
```

## 生产部署（反向代理）

`dist/` 为纯静态产物。生产环境让同一台 nginx/网关同时托管静态文件与 `/api` 反代，保持同源：

```nginx
server {
    listen 80;
    root /srv/mini-judge/dist;

    location / {
        try_files $uri $uri/ /index.html;   # hash 路由其实不依赖 fallback，保留以防换用 history 模式
    }

    # 与 dev proxy 完全同构：/api 原样转发到网关 8085，Bearer 令牌透传，
    # X-User-Id 由网关 JwtGlobalFilter 注入 —— 前端零改动
    location /api/ {
        proxy_pass http://127.0.0.1:8085;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

## 功能总览

- **题库页**：题目卡列表 + EASY/MEDIUM/HARD 难度过滤；
  **「一键初始化演示题」**：注册 demo（用户名已占用 409/40901 容错复用）→ 登录拿 JWT →
  创建 A+B 题 → 浏览器端 `btoa` UTF-8 安全编码两组用例（`1 2→3`、`10 20→30`）上传 →
  自动跳转提交页并选中该题、载入 AC 示例。
  演示账号：`demo / demo123456`。
- **提交页**：textarea 高仿代码编辑器（行号槽滚动同步、等宽字体、聚焦渐变发光、Tab 四空格缩进，
  零编辑器重依赖——不引 monaco/codemirror）；三示例一键切换（正确解→AC / 死循环→TLE / 语法错误→CE）；
  右侧时间线状态机 900ms 轮询 `QUEUED→RUNNING→DONE`（store 内代数令牌防竞态，240s 超时兜底）；
  完成大徽章 + 通过数 / 耗时内存 + 错误详情；历史表点击任意行回看源码载入编辑器。
- **监控页**：3s 轮询 `GET /api/judge/status`；排队/运行大数字变化闪烁，
  手绘 SVG 环形仪表显示 worker 占用率（≥50% 转黄、满负荷转红），40 点双折线 sparkline。
- **全局**：身份徽章（未登录弹登录/注册浮层，已登录下拉退出）；API 日志抽屉
  （Pinia `logs` store 记录每次 fetch 流水：方法/路径/HTTP/业务 code/耗时，上限 200 条）。

## API 契约

统一响应 `{code, msg, data}`（`code=0` 成功），`src/api/http.js` 统一拆封并在失败时抛
`ApiError{http, code, msg}`，同时向日志 store 记流水。网关白名单仅 `/api/auth/**`；
其余接口需 `Authorization: Bearer`（登录接口返回 `data{token,userId,nickname,role}`，
由 auth store 持久化到 localStorage，键名沿用旧版 `mj.*`）。

## 目录结构

```
frontend/
├─ index.html            # Vite 入口（<html class="dark">）
├─ vite.config.js        # port 5174 strictPort + /api → 8085 dev proxy
├─ package.json / .npmrc
├─ src/
│  ├─ main.js            # createApp + Pinia + router + ElementPlus + 暗色 css-vars + tokens.css
│  ├─ App.vue            # 顶栏/身份徽章+登录浮层/左导航/日志抽屉
│  ├─ router/index.js    # 三页签 hash 路由
│  ├─ api/http.js        # fetch 封装（Bearer、X-User-Id、{code,msg,data} 拆封、日志流水）
│  ├─ stores/            # auth.js / submissions.js / logs.js
│  ├─ composables/useProblems.js   # 题库数据模块级共享缓存
│  ├─ styles/tokens.css  # 深空设计令牌 + 徽章体系
│  ├─ components/        # StatusBadge / LogDrawer / GaugeRing / SparkLine
│  ├─ views/             # ProblemsView / SubmitView（懒加载）/ MonitorView（懒加载）
│  └─ utils/b64.js       # UTF-8 安全 btoa、时间格式化
├─ dist/                 # 构建产物（已生成）
└─ legacy-native/        # 旧原生三件套归档
```

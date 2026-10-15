/* ═══════════════════════════════════════════════════════════════════
 * mini-judge · 评测控制台前端逻辑（原生 JS，无框架、无构建步骤，普通 script 直接加载）
 *
 * 架构说明：
 *   - 默认经网关 8085（local profile）访问全部服务：JWT 由网关注入 X-User-Id，
 *     这是最贴近生产形态的调用链；本文件同时兼容“绕过网关直连 8101/8102/8103”
 *     的调试模式（见 CONFIG 与 README.md）。
 *   - 后端统一响应体 {code,msg,data}，code=0 成功；本层在 api() 里统一拆封，
 *     业务代码只拿到 data，错误统一抛 ApiError。
 * ═══════════════════════════════════════════════════════════════════ */
'use strict';

/* ─────────────────────────── API 入口配置 ─────────────────────────── */
/**
 * 默认全部走网关：http://localhost:8085（gateway 模块 local profile，
 * 基线端口 8080 被本机其他进程占用）。网关 globalcors 已放行跨端口 fetch，
 * 页面经静态服务器（任意端口）打开即可直接调用。
 *
 * 直连模式（不经网关调试单服务）：在浏览器控制台执行——
 *   localStorage.setItem('mj.bases', JSON.stringify({
 *     user: 'http://localhost:8101', problem: 'http://localhost:8102', judge: 'http://localhost:8103'}))
 * 然后刷新页面。此时身份头改为前端手工携带 X-User-Id（README 里有安全说明）。
 */
const DEFAULT_BASE = 'http://localhost:8085';
let EXTRA_BASES = {};
try { EXTRA_BASES = JSON.parse(localStorage.getItem('mj.bases') || '{}'); } catch (_) { /* 忽略坏配置 */ }
const CONFIG = {
  user:    EXTRA_BASES.user    || DEFAULT_BASE,
  problem: EXTRA_BASES.problem || DEFAULT_BASE,
  judge:   EXTRA_BASES.judge   || DEFAULT_BASE,
};
const POLL_SUBMISSION_MS = 900;     // 提交结果轮询间隔
const POLL_MONITOR_MS = 3000;       // 监控轮询间隔（规格要求 3s）
const POLL_MAX_WAIT_MS = 240000;    // 单条提交轮询上限（死循环 TLE 用例 + 编译也够用）

/* ─────────────────────────── 全局状态 ─────────────────────────── */
const state = {
  token: localStorage.getItem('mj.token') || null,
  userId: Number(localStorage.getItem('mj.userId')) || null,
  nickname: localStorage.getItem('mj.nickname') || '',
  role: localStorage.getItem('mj.role') || '',
  problems: [],
  filter: '',
  pollTimer: null,
  pollGeneration: 0,      // 取消旧轮询的令牌：新提交/页面切换使其作废
  monTimer: null,
  monitorOn: false,
  samples: [],            // 监控采样 [{q, r}]，保留最近 40 个
  logs: [],               // API 日志 [{t, method, path, http, code, ms, msg}]
  page: 'problems',
};

/* ─────────────────────────── DOM 快捷方式 ─────────────────────────── */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

/* ─────────────────────────── 工具函数 ─────────────────────────── */
function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
/** UTF-8 安全的 base64（btoa 只吃 latin1，先 encodeURIComponent 过一遍） */
function b64(text) {
  return btoa(unescape(encodeURIComponent(text)));
}
function fmtTime(iso) {
  if (!iso) return '—';
  return String(iso).replace('T', ' ').slice(0, 19);
}
function nowStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

let toastTimer = null;
function toast(msg, kind) {
  const el = $('#toast');
  el.textContent = msg;
  el.className = `toast show ${kind || ''}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 2800);
}

class ApiError extends Error {
  constructor(http, code, msg) { super(msg); this.http = http; this.code = code; }
}

/* ─────────────────────────── fetch 封装（带 API 日志） ─────────────────────────── */
/**
 * @param {string} kind   服务归属：user | problem | judge（决定打到哪个 base）
 * @param {string} method HTTP 方法
 * @param {string} path   以 /api 开头的路径
 * @param {object} [body] JSON 请求体
 */
async function api(kind, method, path, body) {
  const url = CONFIG[kind] + path;
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
  // 始终附带 X-User-Id：走网关时它会被 JwtGlobalFilter 剥离并以 JWT 解析结果覆盖
  // （永远覆盖而非缺失才加，见 JwtGlobalFilter 注释）；直连模式下则真正生效。
  if (state.userId) headers['X-User-Id'] = String(state.userId);

  const t0 = performance.now();
  let http = 0, code = -1, msg = '';
  try {
    const res = await fetch(url, {
      method, headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    http = res.status;
    let json = null;
    try { json = await res.json(); } catch (_) { /* 网关 401 是无 body 的空响应 */ }
    code = json ? json.code : (res.ok ? 0 : -1);
    msg = json ? json.msg : (res.ok ? 'ok' : `HTTP ${res.status}`);
    if (http === 401 && !json) { code = 401; msg = '未登录或令牌失效（网关 401）'; }
    if (!res.ok || code !== 0) {
      pushLog(method, path, http, code, performance.now() - t0, msg, true);
      throw new ApiError(http, code, msg);
    }
    pushLog(method, path, http, code, performance.now() - t0, msg, false);
    return json.data;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    // fetch 本身抛异常：网络不可达 / CORS 被拦（浏览器不会给出细分原因）
    msg = `${err.message}（网络不可达或 CORS 被拦，检查 ${CONFIG[kind]} 与后端 CORS 配置）`;
    pushLog(method, path, 0, -1, performance.now() - t0, msg, true);
    throw new ApiError(0, -1, msg);
  }
}

/* ─────────────────────────── API 日志抽屉 ─────────────────────────── */
function pushLog(method, path, http, code, ms, msg, bad) {
  state.logs.unshift({ t: nowStr(), method, path, http, code, ms, msg, bad });
  if (state.logs.length > 200) state.logs.length = 200;
  $('#logCount').textContent = state.logs.length;
  if (!$('#apiDrawer').classList.contains('hidden')) renderLogs();
}
function renderLogs() {
  $('#logList').innerHTML = state.logs.map((l) => `
    <div class="log-entry ${l.bad ? 'fail' : ''}">
      <div class="le-head">
        <span><span class="le-method">${escapeHtml(l.method)}</span>
          <span class="le-path">${escapeHtml(l.path)}</span></span>
        <span>${l.t} · ${Math.round(l.ms)}ms</span>
      </div>
      <div>HTTP ${l.http || '—'} · code ${l.code}
        <span class="${l.bad ? 'le-bad' : 'le-ok'}">${escapeHtml(l.msg)}</span></div>
    </div>`).join('');
}
function toggleDrawer(force) {
  const d = $('#apiDrawer');
  const show = force !== undefined ? force : d.classList.contains('hidden');
  d.classList.toggle('hidden', !show);
  if (show) renderLogs();
}

/* ─────────────────────────── 身份 / 登录 ─────────────────────────── */
function setAuth(data) {
  state.token = data.token;
  state.userId = data.userId;
  state.nickname = data.nickname;
  state.role = data.role;
  localStorage.setItem('mj.token', data.token);
  localStorage.setItem('mj.userId', String(data.userId));
  localStorage.setItem('mj.nickname', data.nickname);
  localStorage.setItem('mj.role', data.role);
  renderIdentity();
}
function clearAuth() {
  state.token = null; state.userId = null; state.nickname = ''; state.role = '';
  ['mj.token', 'mj.userId', 'mj.nickname', 'mj.role'].forEach((k) => localStorage.removeItem(k));
  renderIdentity();
}
function renderIdentity() {
  const btn = $('#identityBtn');
  if (state.token) {
    btn.classList.remove('hidden-logined');
    btn.title = '点击查看身份菜单';
    $('#identityText').textContent = `${state.nickname || 'user'} · ${state.role || 'USER'}`;
  } else {
    $('#identityText').textContent = '登录';
    btn.title = '点击登录';
  }
}
async function doLogin(username, password) {
  const data = await api('user', 'POST', '/api/auth/login', { username, password });
  setAuth(data);
  toast(`欢迎回来，${data.nickname}`, 'ok');
  $('#loginPop').classList.add('hidden');
  onLoggedIn();
}
async function doRegisterAndLogin(username, password, nickname) {
  try {
    await api('user', 'POST', '/api/auth/register', { username, password, nickname: nickname || undefined });
  } catch (e) {
    if (e.code !== 40901) throw e;   // 用户名已占用可继续走登录，其余错误照抛
  }
  await doLogin(username, password);
}
function onLoggedIn() {
  if (state.page === 'problems') loadProblems();
  if (state.page === 'submit') { fillProblemSelect(); loadHistory(); }
}

/* ─────────────────────────── 页面切换 ─────────────────────────── */
function switchPage(page) {
  state.page = page;
  $$('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.page === page));
  $$('.page').forEach((p) => p.classList.toggle('active', p.id === `page-${page}`));
  if (page === 'problems') loadProblems();
  if (page === 'submit') { fillProblemSelect(); loadHistory(); }
  if (page === 'monitor') startMonitor(); else stopMonitor();
}

/* ─────────────────────────── 题库页 ─────────────────────────── */
async function loadProblems() {
  const grid = $('#problemGrid');
  if (!state.token) {
    grid.innerHTML = '';
    $('#problemEmpty').classList.remove('hidden');
    return;
  }
  try {
    const q = state.filter ? `?difficulty=${state.filter}` : '';
    const data = await api('problem', 'GET', '/api/problems' + q);
    state.problems = data || [];
    renderProblemCards();
  } catch (e) {
    grid.innerHTML = '';
    $('#problemEmpty').classList.remove('hidden');
    if (e.http === 401) toast('令牌已失效，请重新登录', 'err');
    else toast('题单加载失败：' + e.message, 'err');
  }
}
function renderProblemCards() {
  const grid = $('#problemGrid');
  const empty = $('#problemEmpty');
  if (!state.problems.length) {
    grid.innerHTML = '';
    empty.classList.remove('hidden');
    return;
  }
  empty.classList.add('hidden');
  grid.innerHTML = state.problems.map((p) => `
    <div class="card problem-card">
      <div class="pc-head">
        <i class="dot ${p.difficulty.toLowerCase()}"></i>
        <span class="diff-label diff-${escapeHtml(p.difficulty)}">${escapeHtml(p.difficulty)}</span>
        <span class="pc-title" title="${escapeHtml(p.title)}">${escapeHtml(p.title)}</span>
        <span class="pc-id">#${p.id}</span>
      </div>
      <p class="pc-desc">${escapeHtml(p.description)}</p>
      <div class="pc-foot">
        <div class="pc-badges">
          <span class="mini-badge" title="单用例 CPU 时限">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/>
            </svg>${p.timeLimitMs} ms
          </span>
          <span class="mini-badge" title="内存上限">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <rect x="7" y="7" width="10" height="10" rx="2"/><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>
            </svg>${p.memoryLimitKb} KB
          </span>
        </div>
        <button class="pc-go" data-goto="${p.id}">去做这题 →</button>
      </div>
    </div>`).join('');
  grid.querySelectorAll('[data-goto]').forEach((b) =>
    b.addEventListener('click', () => {
      switchPage('submit');
      $('#problemSelect').value = b.dataset.goto;
    }));
}

/* 一键初始化演示题：注册 demo 账号 → 登录 → 建 A+B 题 → btoa 编码两组用例上传 */
async function initDemoProblem() {
  const btn = $('#demoInitBtn');
  if (btn.disabled) return;
  btn.disabled = true;
  const original = btn.innerHTML;
  btn.innerHTML = '初始化中…';
  try {
    // 1. demo 账号（已占用则复用现有账号）。
    //    携带 adminBootstrapKey：RBAC 收口后建题/传用例仅 ADMIN——带对 key 新注册即
    //    ADMIN，已存在则服务端幂等提升（与 user-service 的 mini-judge.admin-bootstrap-key
    //    同值；仅限本机演示，生产 key 放配置中心，绝不能打包进前端产物）。
    try {
      await api('user', 'POST', '/api/auth/register',
        { username: 'demo', password: 'demo123456', nickname: '演示账号',
          adminBootstrapKey: 'mini-judge-demo-admin-key-2026' });
    } catch (e) {
      if (e.code !== 40901) throw e;
    }
    // 2. 登录拿 JWT（/api/auth/** 是网关白名单，此步不需要身份头）
    const login = await api('user', 'POST', '/api/auth/login',
      { username: 'demo', password: 'demo123456' });
    setAuth(login);
    // 3. 建题（经网关的写路径：local profile 只拦 GET cases，POST 用例放行）
    const created = await api('problem', 'POST', '/api/problems', {
      title: 'A+B 问题',
      difficulty: 'EASY',
      description: '输入两个整数 a 和 b（同一行，空格分隔），输出 a+b 的和。',
      timeLimitMs: 2000,
      memoryLimitKb: 262144,
      status: 'PUBLISHED',
    });
    // 4. 浏览器端 btoa 编码两组用例：1 2→3 / 10 20→30
    await api('problem', 'POST', `/api/problems/${created.id}/cases`, {
      cases: [
        { inputBase64: b64('1 2\n'),    outputBase64: b64('3\n'),   sample: true  },
        { inputBase64: b64('10 20\n'),  outputBase64: b64('30\n'),  sample: true  },
      ],
    });
    toast(`演示题 #${created.id} 已就绪（demo / demo123456）`, 'ok');
    await loadProblems();
    switchPage('submit');
    fillProblemSelect();
    $('#problemSelect').value = String(created.id);
    loadSample('ac');
  } catch (e) {
    toast('初始化失败：' + e.message, 'err');
  } finally {
    btn.disabled = false;
    btn.innerHTML = original;
  }
}

/* ─────────────────────────── 提交页：编辑器 ─────────────────────────── */
const SAMPLES = {
  ac: [
    'import java.util.Scanner;',
    '',
    'public class Main {',
    '    public static void main(String[] args) {',
    '        Scanner sc = new Scanner(System.in);',
    '        int a = sc.nextInt(), b = sc.nextInt();',
    '        System.out.println(a + b);',
    '    }',
    '}',
  ].join('\n'),
  tle: [
    'public class Main {',
    '    public static void main(String[] args) {',
    '        // 故意死循环：观察 TLE 与监控页 worker 饱和',
    '        long x = 0;',
    '        while (true) { x++; }',
    '    }',
    '}',
  ].join('\n'),
  ce: [
    'public class Main {',
    '    public static void main(String[] args) {',
    '        int a = 1',
    '        System.out.println(a + );   // 两处语法错误，编译期即被拦下',
    '    }',
    '}',
  ].join('\n'),
};

function loadSample(name) {
  $('#codeInput').value = SAMPLES[name];
  syncGutter();
  updateCodeMeta();
}
function syncGutter() {
  const ta = $('#codeInput');
  const lines = ta.value.split('\n').length;
  const gutter = $('#lineGutter');
  const current = gutter.childElementCount;
  if (current !== lines) {
    let html = '';
    for (let i = 1; i <= lines; i++) html += `<div>${i}</div>`;
    gutter.innerHTML = html;
  }
  gutter.scrollTop = ta.scrollTop;
}
function updateCodeMeta() {
  $('#codeMeta').textContent = `${$('#codeInput').value.length} 字符`;
}

/* ─────────────────────────── 提交页：提交流程 ─────────────────────────── */
function fillProblemSelect() {
  const sel = $('#problemSelect');
  const prev = sel.value;
  sel.innerHTML = state.problems.length
    ? state.problems.map((p) => `<option value="${p.id}">#${p.id} ${escapeHtml(p.title)}</option>`).join('')
    : '<option value="">（暂无题目，先去题库页一键初始化）</option>';
  if (prev && state.problems.some((p) => String(p.id) === prev)) sel.value = prev;
}

async function submitCode() {
  const problemId = Number($('#problemSelect').value);
  const code = $('#codeInput').value;
  if (!state.token) { toast('请先登录（或到题库页一键初始化）', 'err'); return; }
  if (!problemId) { toast('请先选择题目', 'err'); return; }
  if (!code.trim()) { toast('代码为空', 'err'); return; }

  const btn = $('#submitBtn');
  btn.disabled = true;
  try {
    const data = await api('judge', 'POST', '/api/submissions', {
      problemId, language: $('#langSelect').value, code,
    });
    toast(`已入队：提交 #${data.submissionId}（身前还有 ${data.queuePosition} 条）`, 'ok');
    startPolling(data.submissionId);
    loadHistory();
  } catch (e) {
    toast('提交失败：' + e.message, 'err');
  } finally {
    btn.disabled = false;
  }
}

/** 轮询状态机：QUEUED → RUNNING → DONE，每 POLL_SUBMISSION_MS 一次 */
function startPolling(submissionId) {
  const gen = ++state.pollGeneration;   // 作废上一轮轮询
  clearInterval(state.pollTimer);
  resetTimeline();
  $('#subIdLabel').textContent = '#' + submissionId;
  $('#resultPanel').classList.add('hidden');
  const t0 = Date.now();

  const tick = async () => {
    if (gen !== state.pollGeneration) { clearInterval(state.pollTimer); return; }
    if (Date.now() - t0 > POLL_MAX_WAIT_MS) {
      clearInterval(state.pollTimer);
      toast('轮询超时：评测仍未结束，可在历史中回看', 'err');
      return;
    }
    try {
      const d = await api('judge', 'GET', `/api/submissions/${submissionId}`);
      if (gen !== state.pollGeneration) return;
      renderTimeline(d.status, d.resultCode);
      if (d.status === 'DONE') {
        clearInterval(state.pollTimer);
        renderResult(d);
        loadHistory();
      }
    } catch (e) {
      if (gen !== state.pollGeneration) return;
      clearInterval(state.pollTimer);
      toast('结果轮询失败：' + e.message, 'err');
    }
  };
  tick();
  state.pollTimer = setInterval(tick, POLL_SUBMISSION_MS);
}

function resetTimeline() {
  $$('#timeline li').forEach((li) => {
    li.classList.remove('done', 'active', 'run');
  });
}
function renderTimeline(status, resultCode) {
  const order = ['QUEUED', 'RUNNING', 'REQUEUE', 'DONE'];
  const idx = status === 'REQUEUE' ? 0 : order.indexOf(status);
  $$('#timeline li').forEach((li, i) => {
    li.classList.remove('done', 'active', 'run');
    if (i < idx) li.classList.add('done');
    else if (i === idx && status !== 'DONE') {
      li.classList.add('active');
      if (status === 'RUNNING') li.classList.add('run');
    }
  });
  if (status === 'DONE') {
    $$('#timeline li').forEach((li) => { li.classList.remove('active', 'run'); li.classList.add('done'); });
    void resultCode;
  }
}
function renderResult(d) {
  $('#resultPanel').classList.remove('hidden');
  const badge = $('#bigBadge');
  badge.textContent = d.resultCode || 'DONE';
  badge.className = 'big-badge ' + (d.resultCode || '');
  $('#passedText').textContent = `${d.passedCases ?? 0}/${d.totalCases ?? 0}`;
  $('#usedText').textContent =
    `${d.timeUsedMs ?? '—'} ms · ${d.memoryUsedKb ?? '—'} KB`;
  const err = $('#errBox');
  if (d.errorMsg) { err.textContent = d.errorMsg; err.classList.remove('hidden'); }
  else err.classList.add('hidden');
  toast(d.resultCode === 'AC' ? 'AC！全部用例通过 🎉' : `判定结果：${d.resultCode}`,
    d.resultCode === 'AC' ? 'ok' : 'err');
}

/* ─────────────────────────── 提交历史 ─────────────────────────── */
async function loadHistory() {
  if (!state.userId) return;
  try {
    const list = await api('judge', 'GET', `/api/submissions?userId=${state.userId}`);
    renderHistory(list || []);
  } catch (e) {
    if (e.http !== 401) toast('历史加载失败：' + e.message, 'err');
  }
}
function statusBadge(s) {
  const key = s.status === 'DONE' ? (s.resultCode || 'DONE') : s.status;
  return `<span class="badge ${escapeHtml(key)}">${escapeHtml(key)}</span>`;
}
function renderHistory(list) {
  const body = $('#histBody');
  $('#histEmpty').classList.toggle('hidden', list.length > 0);
  const titleOf = (pid) => {
    const p = state.problems.find((x) => x.id === pid);
    return p ? p.title : '题 #' + pid;
  };
  body.innerHTML = list.map((s) => `
    <tr data-sub="${s.id}" title="点击回看该次提交源码">
      <td class="mono">#${s.id}</td>
      <td>${escapeHtml(titleOf(s.problemId))}</td>
      <td>${statusBadge(s)}</td>
      <td class="mono">${s.passedCases ?? '—'}/${s.totalCases ?? '—'}</td>
      <td class="mono">${s.timeUsedMs != null ? s.timeUsedMs + ' ms' : '—'}</td>
      <td class="mono">${fmtTime(s.submitTime)}</td>
    </tr>`).join('');
  body.querySelectorAll('tr').forEach((tr) => tr.addEventListener('click', () => {
    const rec = list.find((x) => String(x.id) === tr.dataset.sub);
    if (rec && rec.code) {
      $('#codeInput').value = rec.code;
      syncGutter(); updateCodeMeta();
      if (rec.problemId) $('#problemSelect').value = String(rec.problemId);
      toast(`已载入提交 #${rec.id} 的源码`, 'ok');
    } else {
      toast('该记录未返回源码', 'err');
    }
  }));
}

/* ─────────────────────────── 监控页 ─────────────────────────── */
function startMonitor() {
  if (state.monTimer) return;
  state.monitorOn = true;
  monitorTick();
  state.monTimer = setInterval(monitorTick, POLL_MONITOR_MS);
}
function stopMonitor() {
  state.monitorOn = false;
  clearInterval(state.monTimer);
  state.monTimer = null;
}
let lastQueued = null;
async function monitorTick() {
  try {
    const d = await api('judge', 'GET', '/api/judge/status');
    setNum('#mQueued', d.queued);
    setNum('#mRunning', d.running);
    $('#mBusy').textContent = d.workerBusy;
    $('#mCap').textContent = '/' + d.workerCapacity;

    // 环形仪表：worker 占用率
    const C = 2 * Math.PI * 52;
    const ratio = d.workerCapacity ? d.workerBusy / d.workerCapacity : 0;
    const arc = $('#gaugeArc');
    arc.style.strokeDashoffset = C * (1 - Math.min(ratio, 1));
    arc.classList.toggle('hot', ratio >= 0.5 && ratio < 1);
    arc.classList.toggle('full', ratio >= 1);

    state.samples.push({ q: d.queued, r: d.running });
    if (state.samples.length > 40) state.samples.shift();
    renderSpark();
  } catch (e) {
    // 监控轮询失败不打断（judge-service 可能未启动），仅在数值区提示
    $('#mQueued').textContent = '!';
    $('#mRunning').textContent = '!';
  }
}
function setNum(sel, v) {
  const el = $(sel);
  if (el.textContent !== String(v)) {
    el.textContent = v;
    el.classList.remove('flash');
    void el.offsetWidth;           // 重启动画
    el.classList.add('flash');
  }
}
function renderSpark() {
  const W = 640, H = 120, PAD = 8;
  const max = Math.max(1, ...state.samples.map((s) => Math.max(s.q, s.r)));
  const n = state.samples.length;
  const x = (i) => n === 1 ? W / 2 : PAD + (i / (n - 1)) * (W - 2 * PAD);
  const y = (v) => H - PAD - (v / max) * (H - 2 * PAD);
  const poly = (key) =>
    state.samples.map((s, i) => `${x(i).toFixed(1)},${y(s[key]).toFixed(1)}`).join(' ');
  const grid = [0.25, 0.5, 0.75].map((f) =>
    `<line class="grid-line" x1="0" y1="${(H * f).toFixed(0)}" x2="${W}" y2="${(H * f).toFixed(0)}"/>`).join('');
  $('#spark').innerHTML = `
    ${grid}
    <polyline class="sq" points="${poly('q')}"/>
    <polyline class="sr" points="${poly('r')}"/>`;
}

/* ─────────────────────────── 事件绑定与启动 ─────────────────────────── */
function bind() {
  // 导航
  $$('.nav-btn').forEach((b) => b.addEventListener('click', () => switchPage(b.dataset.page)));

  // 登录入口：身份按钮
  $('#identityBtn').addEventListener('click', () => {
    if (state.token) $('#identityMenu').classList.toggle('hidden');
    else $('#loginPop').classList.toggle('hidden');
  });
  $('#logoutBtn').addEventListener('click', () => {
    clearAuth();
    $('#identityMenu').classList.add('hidden');
    toast('已退出登录', 'ok');
    loadProblems();
  });
  $('#loginDoBtn').addEventListener('click', () => {
    doLogin($('#loginUser').value.trim(), $('#loginPass').value).catch((e) => toast(e.message, 'err'));
  });
  $('#registerDoBtn').addEventListener('click', () => {
    doRegisterAndLogin($('#loginUser').value.trim(), $('#loginPass').value, $('#loginNick').value.trim())
      .catch((e) => toast(e.message, 'err'));
  });
  [$('#loginUser'), $('#loginPass'), $('#loginNick')].forEach((inp) =>
    inp.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') $('#loginDoBtn').click(); }));
  // 点其他区域关闭浮层
  document.addEventListener('click', (ev) => {
    if (!ev.target.closest('.identity-wrap') && !ev.target.closest('#loginPop')) {
      $('#identityMenu').classList.add('hidden');
      $('#loginPop').classList.add('hidden');
    }
  });

  // 题库
  $('#demoInitBtn').addEventListener('click', initDemoProblem);
  $('#refreshProblemsBtn').addEventListener('click', loadProblems);
  $('#diffFilter').addEventListener('click', (ev) => {
    const chip = ev.target.closest('.chip[data-diff]');
    if (!chip) return;
    $$('#diffFilter .chip').forEach((c) => c.classList.remove('active'));
    chip.classList.add('active');
    state.filter = chip.dataset.diff;
    loadProblems();
  });

  // 提交页
  $('#codeInput').addEventListener('input', () => { syncGutter(); updateCodeMeta(); });
  $('#codeInput').addEventListener('scroll', () => { $('#lineGutter').scrollTop = $('#codeInput').scrollTop; });
  $('#codeInput').addEventListener('keydown', (ev) => {   // Tab 缩进
    if (ev.key === 'Tab') {
      ev.preventDefault();
      const ta = ev.target, s = ta.selectionStart;
      ta.value = ta.value.slice(0, s) + '    ' + ta.value.slice(ta.selectionEnd);
      ta.selectionStart = ta.selectionEnd = s + 4;
      syncGutter(); updateCodeMeta();
    }
  });
  $$('.sample-row [data-sample]').forEach((b) =>
    b.addEventListener('click', () => loadSample(b.dataset.sample)));
  $('#submitBtn').addEventListener('click', submitCode);
  $('#refreshHistoryBtn').addEventListener('click', loadHistory);

  // API 日志
  $('#logToggleBtn').addEventListener('click', () => toggleDrawer());
  $('#logCloseBtn').addEventListener('click', () => toggleDrawer(false));
  $('#logClearBtn').addEventListener('click', () => { state.logs = []; $('#logCount').textContent = '0'; renderLogs(); });
}

/* ─────────────────────────── 启动 ─────────────────────────── */
function boot() {
  const directMode = Object.values(CONFIG).some((b) => b !== DEFAULT_BASE);
  $('#apiBaseChip').textContent = directMode
    ? Object.entries(CONFIG).map(([k, v]) => `${k}=${v}`).join(' · ')
    : 'API · 网关 ' + DEFAULT_BASE;
  renderIdentity();
  bind();
  syncGutter();
  updateCodeMeta();
  if (state.token) {
    loadProblems();
  } else {
    $('#problemEmpty').classList.remove('hidden');
  }
}
boot();

function boot() {
  $('#apiBaseChip').textContent =
    `API ${CONFIG.judge === DEFAULT_BASE ? '网关 ' + DEFAULT_BASE : '直连 ' + JSON.stringify(CONFIG)}`;
  renderIdentity();
  bind();
  if (state.token) onLoggedIn(); else loadProblems();
  // 默认进入题库页；监控页定时器仅在页面激活时运行（switchPage 控制）
}
document.addEventListener('DOMContentLoaded', boot);

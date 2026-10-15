/* ═══════════════════════════════════════════════════════════════════
 * fetch 统一封装：
 *   - 所有请求走同源 /api/**，由 Vite dev proxy（或生产反代）转发到网关 8085；
 *     网关解析 JWT 后注入 X-User-Id —— 前端不需要、也不应信任自己声明的身份。
 *   - 统一拆封响应体 {code,msg,data}：code=0 成功返回 data，否则抛 ApiError。
 *   - 每次调用向 Pinia logs store 记一条流水（API 日志抽屉的数据源）。
 * ═══════════════════════════════════════════════════════════════════ */
import { useAuthStore } from '../stores/auth';
import { useLogsStore } from '../stores/logs';

export class ApiError extends Error {
  constructor(http, code, msg) {
    super(msg);
    this.http = http;   // HTTP 状态码（网络不可达时为 0）
    this.code = code;   // 业务 code（-1 表示非业务错误）
  }
}

function nowStr() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

/**
 * @param {string} method HTTP 方法
 * @param {string} path   以 /api 开头的同源路径
 * @param {object} [body] JSON 请求体
 * @returns {Promise<any>} 统一响应中的 data
 */
export async function request(method, path, body) {
  const auth = useAuthStore();
  const logs = useLogsStore();

  const headers = { 'Content-Type': 'application/json' };
  if (auth.token) headers['Authorization'] = 'Bearer ' + auth.token;
  // 始终附带 X-User-Id：走网关时它会被 JwtGlobalFilter 剥离并以 JWT 解析结果覆盖
  // （永远覆盖而非缺失才加）；仅在绕过网关直连单服务的调试模式下才真正生效。
  if (auth.userId) headers['X-User-Id'] = String(auth.userId);

  const t0 = performance.now();
  let http = 0, code = -1, msg = '';
  try {
    const res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    http = res.status;
    let json = null;
    try { json = await res.json(); } catch (_) { /* 网关 401 是无 body 的空响应 */ }
    code = json ? json.code : (res.ok ? 0 : -1);
    msg = json ? json.msg : (res.ok ? 'ok' : `HTTP ${res.status}`);
    if (http === 401 && !json) { code = 401; msg = '未登录或令牌失效（网关 401）'; }

    const ms = performance.now() - t0;
    if (!res.ok || code !== 0) {
      logs.push({ t: nowStr(), method, path, http, code, ms, msg, bad: true });
      throw new ApiError(http, code, msg);
    }
    logs.push({ t: nowStr(), method, path, http, code, ms, msg, bad: false });
    return json.data;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    // fetch 本身抛异常：网络不可达 / 代理未起（浏览器不会给出细分原因）
    msg = `${err.message}（网络不可达或代理异常，检查 dev server 与网关 8085）`;
    logs.push({ t: nowStr(), method, path, http: 0, code: -1, ms: performance.now() - t0, msg, bad: true });
    throw new ApiError(0, -1, msg);
  }
}

export const http = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body),
};

<script setup>
/* 题库页：题目卡列表 + 难度过滤 + 一键初始化演示题
 * 初始化链路（幂等）：注册 demo（409 容错复用）→ 登录拿 JWT →
 * GET /api/problems 按标题查找演示题：存在则直接复用（不重复建题传用例），
 * 不存在才建 A+B 题并上传两组用例 → Toast 区分「已复用/已新建」→
 * 跳转提交页并选中该题。反复点击点击不膨胀题单，可安全地"一键"多次 */
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { http } from '../api/http';
import { useAuthStore } from '../stores/auth';
import { useProblems } from '../composables/useProblems';
import { b64 } from '../utils/b64';

const auth = useAuthStore();
const router = useRouter();
const { problems, filter, loading, loadProblems } = useProblems();

const DIFFS = ['', 'EASY', 'MEDIUM', 'HARD'];
const demoBusy = ref(false);
/* 演示题标题：幂等化查找的匹配键——建题与"查存在"必须用同一常量，
 * 否则两处标题字面量各自漂移一个字符，幂等就静默失效了 */
const DEMO_TITLE = 'A+B 问题';

function fetchList() {
  if (!auth.loggedIn) { problems.value = []; return; }
  loadProblems(auth.token).catch((e) => {
    if (e.http === 401) ElMessage.error('令牌已失效，请重新登录');
    else ElMessage.error('题单加载失败：' + e.message);
  });
}

onMounted(fetchList);
// 登录后（浮层里或初始化演示题时）自动刷新题单
watch(() => auth.token, fetchList);

async function initDemoProblem() {
  if (demoBusy.value) return;
  demoBusy.value = true;
  try {
    // 1. demo 账号（用户名已占用则复用现有账号）。
    //    携带 adminBootstrapKey（RBAC 收口后建题/传用例仅 ADMIN 可用）：
    //    新注册→直接 ADMIN；已存在（40901）→服务端幂等提升为 ADMIN，
    //    与 user-service application.yml 的 mini-judge.admin-bootstrap-key 同值。
    //    注意：该 key 仅适用于本机演示链路（配置明文可见）；生产环境 key 放
    //    配置中心且绝不能打包进前端产物，管理账号应由运维在后台创建。
    try {
      await http.post('/api/auth/register', {
        username: 'demo', password: 'demo123456', nickname: '演示账号',
        adminBootstrapKey: 'demo-only-change-me',
      });
    } catch (e) {
      if (e.code !== 40901 && e.http !== 409) throw e;
    }
    // 2. 登录拿 JWT（/api/auth/** 是网关白名单，此步不需要身份头）。
    //    幂等流程里登录仍必须在"查重复用"之前：GET /api/problems 也要带 JWT，
    //    且"已存在→复用"分支同样要保证登录后才能跳提交页做题
    await auth.login('demo', 'demo123456');
    // 3. 幂等查找：向服务端拉全量已发布列表按标题找演示题。
    //    刻意不用本地 problems 缓存——它可能被难度过滤/陈旧状态污染，
    //    "是否已存在"这种写前查重必须以服务端实况为准
    const existing = ((await http.get('/api/problems')) || [])
      .find((p) => p.title === DEMO_TITLE);
    let demoId;
    let reused;
    if (existing) {
      // 4a. 演示题已在：直接复用，不再建题也不再传用例（连点不膨胀题单）
      demoId = existing.id;
      reused = true;
    } else {
      // 4b. 首次初始化才建题（经网关的写路径：local profile 只拦 GET cases，POST 用例放行）
      // dedupe:true 让后端也兜底幂等——万一前端查重与建题之间有并发窗口，
      // 后端按 title+PUBLISHED 查重命中时直接复用并返回 reused:true
      const created = await http.post('/api/problems', {
        title: DEMO_TITLE,
        difficulty: 'EASY',
        description: '输入两个整数 a 和 b（同一行，空格分隔），输出 a+b 的和。',
        timeLimitMs: 2000,
        memoryLimitKb: 262144,
        status: 'PUBLISHED',
        dedupe: true,
      });
      demoId = created.id;
      // 对齐后端 dedupe 契约：后端命中复用时响应带 reused:true——以前端本地
      // 判断为主、后端字段兜底（并行窗口下后端可能替我们复用了已有题）
      reused = created.reused === true;
      // 5. 浏览器端 btoa 编码两组用例：1 2→3 / 10 20→30
      await http.post(`/api/problems/${demoId}/cases`, {
        cases: [
          { inputBase64: b64('1 2\n'), outputBase64: b64('3\n'), sample: true },
          { inputBase64: b64('10 20\n'), outputBase64: b64('30\n'), sample: true },
        ],
      });
    }
    // Toast 区分两条路径：复用不打扰用户"为什么又有新题"，新建给出就绪提示
    ElMessage.success(reused
      ? `已复用演示题 #${demoId}（demo / demo123456）`
      : `已新建演示题 #${demoId}（demo / demo123456）`);
    await loadProblems(auth.token);
    router.push({ path: '/submit', query: { problem: String(demoId), sample: 'ac' } });
  } catch (e) {
    ElMessage.error('初始化失败：' + e.message);
  } finally {
    demoBusy.value = false;
  }
}

function gotoProblem(id) {
  router.push({ path: '/submit', query: { problem: String(id) } });
}
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <h2>题库</h2>
        <p class="muted page-desc">已发布题目一览 · 选择难度过滤，点击卡片进入提交</p>
      </div>
      <button class="btn-gradient demo-btn" :disabled="demoBusy" @click="initDemoProblem">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M13 2 4.5 13.5H11L9.5 22 19 9.5h-6.5z" />
        </svg>
        {{ demoBusy ? '初始化中…' : '一键初始化演示题（A+B）' }}
      </button>
    </div>

    <div class="filter-row">
      <button v-for="d in DIFFS" :key="d" class="chip" :class="[{ active: filter === d }, d ? 'diff-' + d.toLowerCase() : '']"
        @click="filter = d; fetchList()">
        {{ d || '全部' }}
      </button>
      <button class="chip" title="刷新题单" @click="fetchList">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6" />
        </svg>
      </button>
    </div>

    <div v-loading="loading" class="problem-grid">
      <div v-for="p in problems" :key="p.id" class="glass card problem-card">
        <div class="pc-head">
          <span class="pc-id mono">#{{ p.id }}</span>
          <span class="pc-title" :title="p.title">{{ p.title }}</span>
          <span class="diff-badge" :class="p.difficulty">{{ p.difficulty }}</span>
        </div>
        <p class="pc-desc">{{ p.description }}</p>
        <div class="pc-foot">
          <div class="pc-badges">
            <span class="mini-badge mono" title="单用例 CPU 时限">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 3" />
              </svg>{{ p.timeLimitMs }} ms
            </span>
            <span class="mini-badge mono" title="内存上限">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <rect x="7" y="7" width="10" height="10" rx="2" /><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4" />
              </svg>{{ p.memoryLimitKb }} KB
            </span>
          </div>
          <button class="pc-go" @click="gotoProblem(p.id)">去做这题 →</button>
        </div>
      </div>
    </div>

    <div v-if="!problems.length" class="glass empty">
      <p>{{ auth.loggedIn ? '还没有可做的题。' : '未登录，题单不可见。' }}</p>
      <p class="muted">点击右上方「一键初始化演示题」：自动注册 demo 账号 → 登录 → 建 A+B 题 → 上传两组测试用例，一条链路全部打通。</p>
    </div>
  </section>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 21px; color: var(--mj-text); }
.page-desc { margin: 0; font-size: 13px; }
.demo-btn { display: inline-flex; align-items: center; gap: 8px; padding: 9px 18px; font-size: 13.5px; }

.filter-row { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }

.problem-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px; min-height: 80px;
}
.problem-card { padding: 16px; display: flex; flex-direction: column; gap: 10px; transition: border-color 0.15s, box-shadow 0.15s; }
.problem-card:hover { border-color: var(--mj-border-strong); box-shadow: 0 3px 12px rgba(0, 0, 0, 0.07); }
.pc-head { display: flex; align-items: center; gap: 8px; }
.pc-id { color: var(--mj-faint); font-size: 12.5px; }
.pc-title { font-weight: 600; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--mj-text); }
.pc-desc { margin: 0; font-size: 13px; color: var(--mj-muted); line-height: 1.55;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pc-foot { display: flex; justify-content: space-between; align-items: center; margin-top: auto; }
.pc-badges { display: flex; gap: 8px; }
.mini-badge { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; color: var(--mj-muted);
  border: 1px solid var(--mj-border); border-radius: 999px; padding: 3px 9px; }
.pc-go {
  background: transparent; border: 1px solid rgba(38, 179, 92, 0.5); color: var(--mj-ac);
  border-radius: var(--mj-radius); padding: 5px 12px; font-size: 12.5px; font-weight: 600;
  cursor: pointer; transition: all 0.15s;
}
.pc-go:hover { background: rgba(38, 179, 92, 0.1); }

.empty { padding: 46px 30px; text-align: center; margin-top: 4px; }
.empty p { margin: 6px 0; }
</style>

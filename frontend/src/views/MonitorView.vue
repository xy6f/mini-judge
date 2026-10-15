<script setup>
/* 监控页：每 3s 轮询 GET /api/judge/status
 * 队列/运行大数字（变化闪烁）、手绘 SVG 环形仪表（worker 占用率）、40 点 sparkline */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { http } from '../api/http';
import { useAuthStore } from '../stores/auth';
import GaugeRing from '../components/GaugeRing.vue';
import SparkLine from '../components/SparkLine.vue';

const auth = useAuthStore();
const router = useRouter();

const POLL_MONITOR_MS = 3000;

const queued = ref(0);
const running = ref(0);
const busy = ref(0);
const capacity = ref(0);
const unreachable = ref(false);
const samples = ref([]);   // 最近 40 个 {q, r}

const qFlash = ref(false);
const rFlash = ref(false);
let timer = null;

function setFlash(flashRef) {
  flashRef.value = false;
  requestAnimationFrame(() => { flashRef.value = true; });
}

async function tick() {
  if (!auth.loggedIn) return;   // 未登录时该接口走网关会被 401，不打无谓的请求
  try {
    const d = await http.get('/api/judge/status');
    unreachable.value = false;
    if (String(queued.value) !== String(d.queued)) { queued.value = d.queued; setFlash(qFlash); }
    if (String(running.value) !== String(d.running)) { running.value = d.running; setFlash(rFlash); }
    busy.value = d.workerBusy;
    capacity.value = d.workerCapacity;
    samples.value.push({ q: d.queued, r: d.running });
    if (samples.value.length > 40) samples.value.shift();
  } catch (e) {
    // 监控轮询失败不打断（judge-service 可能未启动），仅置为 !
    unreachable.value = true;
  }
}

/** 按登录态启停轮询：登录后立即刷新，登出后停表并清空展示数据 */
function syncPolling() {
  clearInterval(timer);
  timer = null;
  if (!auth.loggedIn) {
    unreachable.value = false;
    queued.value = 0;
    running.value = 0;
    busy.value = 0;
    capacity.value = 0;
    samples.value = [];
    return;
  }
  tick();
  timer = setInterval(tick, POLL_MONITOR_MS);
}

onMounted(syncPolling);
onBeforeUnmount(() => clearInterval(timer));
watch(() => auth.loggedIn, syncPolling);
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <h2>评测机监控</h2>
        <p class="muted page-desc">每 3s 轮询 <code class="mono">GET /api/judge/status</code>，观察队列深度与 worker 忙闲</p>
      </div>
      <span v-if="auth.loggedIn" class="live-dot" title="轮询进行中"><i></i>LIVE</span>
    </div>

    <!-- 未登录：judge 状态接口需登录，给出友好引导而非报错 -->
    <div v-if="!auth.loggedIn" class="glass empty">
      <p>登录后可查看评测机的队列深度与 worker 占用。</p>
      <p class="muted">该接口经网关鉴权（JWT），未登录时不发起轮询。</p>
      <button class="btn-gradient login-cta" @click="router.push('/problems')">去登录 / 一键初始化演示题</button>
    </div>

    <div v-else class="metric-row">
      <div class="glass metric">
        <p class="metric-label">排队 QUEUED</p>
        <p class="metric-num mono" :class="{ flash: qFlash }">{{ unreachable ? '!' : queued }}</p>
      </div>
      <div class="glass metric">
        <p class="metric-label">评测中 RUNNING</p>
        <p class="metric-num mono" :class="{ flash: rFlash }">{{ unreachable ? '!' : running }}</p>
      </div>
      <div class="glass metric gauge-card">
        <p class="metric-label">Worker 忙 / 容量</p>
        <GaugeRing :busy="busy" :capacity="capacity" />
      </div>
    </div>

    <div v-if="auth.loggedIn" class="glass spark-card">
      <div class="card-head">
        <h3>队列采样曲线</h3>
        <span class="legend"><i class="lg lg-q"></i>排队</span>
        <span class="legend"><i class="lg lg-r"></i>评测中</span>
      </div>
      <SparkLine :samples="samples" />
      <p class="muted tip">保留最近 40 次采样；提交几条死循环代码，就能亲眼看到队列爬坡、worker 饱和。</p>
    </div>
  </section>
</template>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h2 { margin: 0 0 4px; font-size: 21px; }
.page-desc { margin: 0; font-size: 13px; }
.page-desc code { color: var(--mj-cyan); font-size: 12px; }

.metric-row { display: grid; grid-template-columns: 1fr 1fr auto; gap: 14px; margin-bottom: 14px; }

.empty { padding: 46px 30px; text-align: center; }
.empty p { margin: 6px 0; }
.login-cta { margin-top: 14px; padding: 9px 22px; font-size: 13.5px; color: #fff; }
@media (max-width: 900px) { .metric-row { grid-template-columns: 1fr; } }
.metric { padding: 18px 22px; }
.metric-label { margin: 0 0 6px; font-size: 12px; letter-spacing: 0.08em; color: var(--mj-muted); }
.metric-num { margin: 0; font-size: 46px; font-weight: 700; line-height: 1.1;
  background: var(--mj-grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
.metric-num.flash { animation: mj-flash 0.5s ease; }
.gauge-card { display: flex; flex-direction: column; align-items: center; }

.spark-card { padding: 16px 18px; }
.card-head { display: flex; align-items: center; gap: 16px; margin-bottom: 10px; }
.card-head h3 { margin: 0; font-size: 14.5px; }
.legend { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--mj-muted); }
.lg { width: 14px; height: 3px; border-radius: 2px; display: inline-block; }
.lg-q { background: var(--mj-queued); }
.lg-r { background: var(--mj-cyan); }
.tip { font-size: 11.5px; margin: 8px 0 0; }
</style>

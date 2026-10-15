<script setup>
/* 提交页：左侧高仿代码编辑器（textarea + 行号槽，零重依赖）
 * 右侧时间线状态机（900ms 轮询 QUEUED→RUNNING→DONE，代数防竞态在 store 里）
 * 完成大徽章 + 通过数/耗时 + 历史表点击回看源码 */
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { http } from '../api/http';
import { useAuthStore } from '../stores/auth';
import { useSubmissionsStore } from '../stores/submissions';
import { useProblems } from '../composables/useProblems';
import StatusBadge from '../components/StatusBadge.vue';
import { fmtTime } from '../utils/b64';

const auth = useAuthStore();
const sub = useSubmissionsStore();
const route = useRoute();
const { problems, loadProblems } = useProblems();

/* ── 示例代码：一键切换 AC / TLE / CE 三种演示剧本 ── */
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

/* ── 编辑器状态 ── */
const code = ref(SAMPLES.ac);
const problemId = ref(null);
const language = ref('JAVA17');
const gutterScroll = ref(0);
const submitting = ref(false);

const lineCount = computed(() => code.value.split('\n').length);
const charCount = computed(() => code.value.length);

function loadSample(name) { code.value = SAMPLES[name]; }

function onEditorScroll(e) { gutterScroll.value = e.target.scrollTop; }

function onTab(ev) {   // Tab 缩进 4 空格
  if (ev.key !== 'Tab') return;
  ev.preventDefault();
  const ta = ev.target, s = ta.selectionStart;
  code.value = code.value.slice(0, s) + '    ' + code.value.slice(ta.selectionEnd);
  requestAnimationFrame(() => { ta.selectionStart = ta.selectionEnd = s + 4; });
}

/* ── 时间线状态机视图 ── */
const STEPS = ['QUEUED', 'RUNNING', 'DONE'];
const activeIdx = computed(() => {
  const st = sub.status;
  if (!st) return -1;
  if (st === 'DONE') return 3;                 // 全部完成
  if (st === 'REQUEUE') return 0;
  return STEPS.indexOf(st);
});
function stepClass(i) {
  if (activeIdx.value > i || activeIdx.value === 3) return 'done';
  if (activeIdx.value === i) return sub.status === 'RUNNING' ? 'active run' : 'active';
  return '';
}

/* ── 提交流程 ── */
async function submitCode() {
  if (!auth.loggedIn) { ElMessage.error('请先登录（或到题库页一键初始化）'); return; }
  if (!problemId.value) { ElMessage.error('请先选择题目'); return; }
  if (!code.value.trim()) { ElMessage.error('代码为空'); return; }
  submitting.value = true;
  try {
    const data = await http.post('/api/submissions', {
      problemId: problemId.value, language: language.value, code: code.value,
    });
    ElMessage.success(`已入队：提交 #${data.submissionId}（身前还有 ${data.queuePosition} 条）`);
    sub.startPolling(data.submissionId);
    loadHistory();
  } catch (e) {
    ElMessage.error('提交失败：' + e.message);
  } finally {
    submitting.value = false;
  }
}

/* 轮询结束事件：DONE 弹判定 toast；超时/失败弹错误 */
watch(() => sub.polling, (now, prev) => {
  if (prev && !now) {
    if (sub.pollError) { ElMessage.error(sub.pollError); sub.pollError = ''; }
  }
});
watch(() => sub.detail, (d) => {
  if (!d) return;
  ElMessage({
    message: d.resultCode === 'AC' ? 'AC！全部用例通过 🎉' : `判定结果：${d.resultCode}`,
    type: d.resultCode === 'AC' ? 'success' : 'error',
    duration: 2800,
  });
  loadHistory();
});

/* ── 提交历史 ── */
const history = ref([]);
const histLoading = ref(false);
async function loadHistory() {
  if (!auth.userId) return;
  histLoading.value = true;
  try {
    const list = await http.get(`/api/submissions?userId=${auth.userId}`);
    history.value = list || [];
  } catch (e) {
    if (e.http !== 401) ElMessage.error('历史加载失败：' + e.message);
  } finally {
    histLoading.value = false;
  }
}
const titleOf = (pid) => {
  const p = problems.value.find((x) => x.id === pid);
  return p ? p.title : '题 #' + pid;
};
function onRowClick(row) {   // 点击回看该次提交源码
  if (row.code) {
    code.value = row.code;
    if (row.problemId) problemId.value = row.problemId;
    ElMessage.success(`已载入提交 #${row.id} 的源码`);
  } else {
    ElMessage.warning('该记录未返回源码');
  }
}

/* ── 挂载：拉题单 + 历史；支持题库页跳转带来的 problem/sample 参数 ── */
onMounted(() => {
  const init = async () => {
    if (auth.loggedIn) await loadProblems(auth.token).catch(() => {});
    const q = route.query.problem;
    if (q && problems.value.some((p) => String(p.id) === String(q))) problemId.value = Number(q);
    else if (problems.value.length) problemId.value = problems.value[0].id;
    if (route.query.sample && SAMPLES[route.query.sample]) loadSample(route.query.sample);
    await loadHistory();
  };
  init();
  sub.reset();   // 离开再回来不残留旧轮询
});
onBeforeUnmount(() => sub.stopPolling());
watch(() => auth.token, () => { loadProblems(auth.token).catch(() => {}); loadHistory(); });
</script>

<template>
  <section class="page">
    <div class="submit-grid">

      <!-- ══ 左：编辑器卡片 ══ -->
      <div class="glass card editor-card">
        <div class="card-head">
          <h3>代码编辑</h3>
          <span class="muted mono meta">{{ charCount }} 字符</span>
        </div>
        <div class="editor-tools">
          <el-select v-model="problemId" placeholder="选择题目" class="tool-select">
            <el-option v-for="p in problems" :key="p.id" :label="`#${p.id} ${p.title}`" :value="p.id" />
            <template #empty><p class="sel-empty">（暂无题目，先去题库页一键初始化）</p></template>
          </el-select>
          <el-select v-model="language" class="tool-select narrow">
            <el-option label="Java 17" value="JAVA17" />
          </el-select>
        </div>
        <div class="sample-row">
          <span class="sample-label muted">示例代码：</span>
          <button class="chip tiny" @click="loadSample('ac')">正确解 → AC</button>
          <button class="chip tiny" @click="loadSample('tle')">死循环 → TLE</button>
          <button class="chip tiny" @click="loadSample('ce')">语法错误 → CE</button>
        </div>

        <div class="editor">
          <div class="line-gutter mono" :style="{ transform: `translateY(${-gutterScroll}px)` }">
            <div v-for="n in lineCount" :key="n">{{ n }}</div>
          </div>
          <textarea
            v-model="code"
            class="code-area mono"
            spellcheck="false"
            autocomplete="off"
            placeholder="在此粘贴 / 编写 Java 代码（public class Main，从标准输入读，向标准输出写）"
            @scroll="onEditorScroll"
            @keydown="onTab"
          ></textarea>
        </div>

        <div class="editor-foot">
          <button class="btn-gradient submit-btn" :disabled="submitting || sub.polling" @click="submitCode">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m5 12 5 5L21 6" />
            </svg>
            {{ sub.polling ? '评测中…' : '提交评测' }}
          </button>
          <span class="muted tip">提交接口秒回：只入队，评测在后台 worker 异步完成</span>
        </div>
      </div>

      <!-- ══ 右：时间线 + 结果 + 历史 ══ -->
      <div class="result-col">
        <div class="glass card timeline-card">
          <div class="card-head">
            <h3>评测进度</h3>
            <span class="mono muted">{{ sub.submissionId ? '#' + sub.submissionId : '—' }}</span>
          </div>
          <ol class="timeline">
            <li v-for="(step, i) in STEPS" :key="step" :class="stepClass(i)">
              <i class="tl-dot"></i>
              <div class="tl-text">
                <b>{{ step }}</b>
                <span class="muted">{{ ['已入队，等待 worker 领取', '沙箱编译并逐用例评测中', '判定完成'][i] }}</span>
              </div>
            </li>
          </ol>

          <div v-if="sub.detail" class="result-panel">
            <div class="verdict-row">
              <span class="big-badge" :class="sub.detail.resultCode || 'DONE'">{{ sub.detail.resultCode || 'DONE' }}</span>
              <div class="verdict-stats">
                <p><span class="stat-num mono">{{ sub.detail.passedCases ?? 0 }}/{{ sub.detail.totalCases ?? 0 }}</span> 通过用例</p>
                <p class="mono muted">{{ sub.detail.timeUsedMs ?? '—' }} ms · {{ sub.detail.memoryUsedKb ?? '—' }} KB</p>
              </div>
            </div>
            <pre v-if="sub.detail.errorMsg" class="err-box mono">{{ sub.detail.errorMsg }}</pre>
          </div>
        </div>

        <div class="glass card history-card">
          <div class="card-head">
            <h3>提交历史</h3>
            <button class="chip" title="刷新历史" @click="loadHistory">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6" />
              </svg>
            </button>
          </div>
          <el-table :data="history" v-loading="histLoading" size="small" class="hist-table"
            @row-click="onRowClick" row-key="id">
            <el-table-column prop="id" label="#" width="52">
              <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="题目" min-width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ titleOf(row.problemId) }}</template>
            </el-table-column>
            <el-table-column label="结果" width="90">
              <template #default="{ row }"><StatusBadge :status="row.status" :result-code="row.resultCode" /></template>
            </el-table-column>
            <el-table-column label="通过" width="76">
              <template #default="{ row }"><span class="mono">{{ row.passedCases ?? '—' }}/{{ row.totalCases ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="耗时" width="84">
              <template #default="{ row }"><span class="mono">{{ row.timeUsedMs != null ? row.timeUsedMs + ' ms' : '—' }}</span></template>
            </el-table-column>
            <el-table-column label="提交时间" min-width="140">
              <template #default="{ row }"><span class="mono">{{ fmtTime(row.submitTime) }}</span></template>
            </el-table-column>
            <template #empty><p class="muted">暂无提交记录</p></template>
          </el-table>
          <p class="muted tip">点击任意行可回看该次提交的源码（载入左侧编辑器）</p>
        </div>
      </div>

    </div>
  </section>
</template>

<style scoped>
.submit-grid { display: grid; grid-template-columns: minmax(420px, 1.25fr) minmax(360px, 1fr); gap: 14px; align-items: start; }
@media (max-width: 1100px) { .submit-grid { grid-template-columns: 1fr; } }

.card { padding: 16px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.card-head h3 { margin: 0; font-size: 14.5px; }
.meta { font-size: 12px; }
.tip { font-size: 11.5px; margin: 8px 0 0; }

.editor-tools { display: flex; gap: 10px; margin-bottom: 10px; }
.tool-select { flex: 1; }
.tool-select.narrow { flex: 0 0 120px; }
.sel-empty { padding: 10px; font-size: 12.5px; color: var(--mj-muted); text-align: center; }

.sample-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.sample-label { font-size: 12.5px; }
.chip.tiny { padding: 3px 10px; font-size: 11.5px; }

/* ── 高仿编辑器：textarea + 行号槽，聚焦发光，无任何重依赖 ── */
.editor {
  display: flex; position: relative; height: 320px; overflow: hidden;
  border: 1px solid var(--mj-border); border-radius: 10px;
  background: rgba(2, 6, 23, 0.55);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.editor:focus-within {
  border-color: var(--mj-border-hi);
  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.16), 0 0 24px rgba(34, 211, 238, 0.10);
}
.line-gutter {
  flex: 0 0 44px; padding: 12px 0; text-align: right; user-select: none; pointer-events: none;
  color: rgba(138, 147, 173, 0.55); font-size: 13px; line-height: var(--mj-line-h);
  border-right: 1px solid var(--mj-border); background: rgba(255, 255, 255, 0.02);
  position: relative; z-index: 1;
}
.line-gutter div { padding-right: 10px; height: var(--mj-line-h); }
.code-area {
  flex: 1; padding: 12px 14px; border: none; outline: none; resize: none; background: transparent;
  color: #dbe3f5; caret-color: var(--mj-cyan);
  font-size: 13px; line-height: var(--mj-line-h); tab-size: 4;
  white-space: pre; overflow: auto;
}
.code-area::placeholder { color: rgba(138, 147, 173, 0.45); }

.editor-foot { display: flex; align-items: center; gap: 14px; margin-top: 12px; }
.submit-btn { display: inline-flex; align-items: center; gap: 8px; padding: 10px 26px; font-size: 14px; }

.result-col { display: flex; flex-direction: column; gap: 14px; }

/* ── 时间线状态机 ── */
.timeline { list-style: none; margin: 0; padding: 0; }
.timeline li { display: flex; gap: 12px; align-items: flex-start; padding: 9px 0; position: relative; }
.timeline li::before {  /* 竖线 */
  content: ''; position: absolute; left: 7px; top: 30px; bottom: -9px; width: 2px;
  background: var(--mj-border);
}
.timeline li:last-child::before { display: none; }
.tl-dot {
  flex: 0 0 16px; width: 16px; height: 16px; border-radius: 50%; margin-top: 3px;
  border: 2px solid var(--mj-border); background: var(--mj-bg); transition: all 0.25s;
}
.tl-text b { display: block; font-family: var(--mj-code-font); font-size: 13px; color: var(--mj-muted); transition: color 0.25s; }
.tl-text span { font-size: 12px; }
/* 已完成：渐变实心点 */
.timeline li.done .tl-dot { border-color: var(--mj-cyan); background: var(--mj-grad); box-shadow: 0 0 10px rgba(34, 211, 238, 0.4); }
.timeline li.done .tl-text b { color: var(--mj-text); }
/* 进行中：呼吸蓝；RUNNING 时转 cyan */
.timeline li.active .tl-dot { border-color: var(--mj-queued); background: var(--mj-queued); animation: mj-breathe 1.8s ease-in-out infinite; }
.timeline li.active.run .tl-dot { border-color: var(--mj-cyan); background: var(--mj-cyan); box-shadow: 0 0 0 4px rgba(34, 211, 238, 0.18); animation: mj-spin-dot 1.1s linear infinite; }
.timeline li.active .tl-text b { color: #fff; }
@keyframes mj-spin-dot {
  0% { box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.35); }
  50% { box-shadow: 0 0 0 6px rgba(34, 211, 238, 0.12); }
  100% { box-shadow: 0 0 0 0 rgba(34, 211, 238, 0.35); }
}

.result-panel { margin-top: 14px; border-top: 1px dashed var(--mj-border); padding-top: 14px; }
.verdict-row { display: flex; align-items: center; gap: 18px; }
.verdict-stats p { margin: 3px 0; font-size: 13px; }
.stat-num { font-size: 20px; color: var(--mj-cyan); }
.err-box {
  margin: 12px 0 0; padding: 10px 12px; border-radius: 8px; font-size: 12px; line-height: 1.5;
  background: rgba(248, 113, 113, 0.08); border: 1px solid rgba(248, 113, 113, 0.3);
  color: #fca5a5; white-space: pre-wrap; word-break: break-all; max-height: 180px; overflow: auto;
}

.history-card :deep(.el-table__row) { cursor: pointer; }
.hist-table { width: 100%; }
</style>

<script setup>
/* API 调用日志抽屉：数据源是 Pinia logs store（http.js 每次 fetch 记一条流水） */
import { useLogsStore } from '../stores/logs';

const logs = useLogsStore();
</script>

<template>
  <el-drawer
    :model-value="logs.drawerOpen"
    title="API 调用日志"
    direction="rtl"
    size="380px"
    @update:model-value="(v) => logs.toggleDrawer(v)"
  >
    <template #header>
      <div class="drawer-head">
        <h3>API 调用日志</h3>
        <div class="drawer-ops">
          <button class="chip" @click="logs.clear()">清空</button>
          <button class="chip" @click="logs.toggleDrawer(false)">收起</button>
        </div>
      </div>
    </template>
    <div class="log-list">
      <p v-if="!logs.entries.length" class="muted center">暂无调用记录</p>
      <div v-for="(l, i) in logs.entries" :key="i" class="log-entry" :class="{ fail: l.bad }">
        <div class="le-head">
          <span><span class="le-method mono">{{ l.method }}</span>
            <span class="le-path mono">{{ l.path }}</span></span>
          <span class="muted">{{ l.t }} · {{ Math.round(l.ms) }}ms</span>
        </div>
        <div class="le-tail muted">HTTP {{ l.http || '—' }} · code {{ l.code }}
          <span :class="l.bad ? 'le-bad' : 'le-ok'">{{ l.msg }}</span>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.drawer-head h3 { margin: 0; font-size: 15px; }
.drawer-ops { display: flex; gap: 8px; }
.center { text-align: center; padding: 30px 0; }
.log-entry {
  border: 1px solid var(--mj-border); border-radius: 10px;
  padding: 9px 12px; margin-bottom: 9px; font-size: 12.5px;
  background: var(--mj-panel);
}
.log-entry.fail { border-color: rgba(248, 113, 113, 0.4); background: rgba(248, 113, 113, 0.06); }
.le-head { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
.le-method { color: var(--mj-violet); font-weight: 700; margin-right: 6px; }
.le-path { color: var(--mj-text); word-break: break-all; }
.le-ok { color: var(--mj-ac); margin-left: 6px; }
.le-bad { color: var(--mj-tle); margin-left: 6px; }
</style>

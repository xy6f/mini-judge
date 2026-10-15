<script setup>
/* 手绘 SVG 环形仪表：worker 占用率（busy/capacity），≥50% 变暖色、满负荷红 */
import { computed } from 'vue';

const props = defineProps({
  busy: { type: Number, default: 0 },
  capacity: { type: Number, default: 0 },
});

const C = 2 * Math.PI * 52;
const ratio = computed(() => (props.capacity ? Math.min(props.busy / props.capacity, 1) : 0));
const offset = computed(() => C * (1 - ratio.value));
const cls = computed(() => {
  if (ratio.value >= 1) return 'full';
  if (ratio.value >= 0.5) return 'hot';
  return '';
});
</script>

<template>
  <div class="gauge">
    <svg viewBox="0 0 120 120" class="gauge-svg">
      <circle class="gauge-bg" cx="60" cy="60" r="52" />
      <circle class="gauge-val" :class="cls" cx="60" cy="60" r="52"
        :stroke-dasharray="C" :stroke-dashoffset="offset" />
    </svg>
    <div class="gauge-text mono"><b>{{ busy }}</b><span class="muted">/{{ capacity }}</span></div>
  </div>
</template>

<style scoped>
.gauge { position: relative; width: 140px; height: 140px; }
.gauge-svg { width: 100%; height: 100%; transform: rotate(-90deg); }
.gauge-bg { fill: none; stroke: rgba(255, 255, 255, 0.07); stroke-width: 10; }
.gauge-val {
  fill: none; stroke: var(--mj-cyan); stroke-width: 10; stroke-linecap: round;
  transition: stroke-dashoffset 0.6s ease, stroke 0.3s;
  filter: drop-shadow(0 0 6px rgba(34, 211, 238, 0.5));
}
.gauge-val.hot { stroke: var(--mj-wa); filter: drop-shadow(0 0 6px rgba(251, 191, 36, 0.5)); }
.gauge-val.full { stroke: var(--mj-tle); filter: drop-shadow(0 0 8px rgba(248, 113, 113, 0.6)); }
.gauge-text {
  position: absolute; inset: 0; display: flex; align-items: baseline; justify-content: center;
  font-size: 26px;
}
.gauge-text b { color: var(--mj-text); }
</style>

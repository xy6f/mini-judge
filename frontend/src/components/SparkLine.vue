<script setup>
/* 手绘 SVG sparkline：最近 40 个采样点的排队/运行双折线 */
import { computed } from 'vue';

const props = defineProps({
  samples: { type: Array, default: () => [] },   // [{q, r}]
});

const W = 640, H = 120, PAD = 8;

const model = computed(() => {
  const s = props.samples;
  const max = Math.max(1, ...s.map((v) => Math.max(v.q, v.r)));
  const n = s.length;
  const x = (i) => (n === 1 ? W / 2 : PAD + (i / (n - 1)) * (W - 2 * PAD));
  const y = (v) => H - PAD - (v / max) * (H - 2 * PAD);
  const poly = (key) => s.map((it, i) => `${x(i).toFixed(1)},${y(it[key]).toFixed(1)}`).join(' ');
  return {
    q: poly('q'),
    r: poly('r'),
    grid: [0.25, 0.5, 0.75].map((f) => ({ y1: (H * f).toFixed(0), y2: (H * f).toFixed(0) })),
  };
});
</script>

<template>
  <svg class="spark" :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none">
    <line v-for="(g, i) in model.grid" :key="i" class="grid-line" x1="0" :y1="g.y1" :x2="W" :y2="g.y2" />
    <polyline class="sq" :points="model.q" />
    <polyline class="sr" :points="model.r" />
  </svg>
</template>

<style scoped>
.spark { width: 100%; height: 120px; display: block; }
.grid-line { stroke: rgba(255, 255, 255, 0.06); stroke-width: 1; }
.sq { fill: none; stroke: var(--mj-queued); stroke-width: 2; stroke-linejoin: round; filter: drop-shadow(0 0 4px rgba(96, 165, 250, 0.4)); }
.sr { fill: none; stroke: var(--mj-cyan); stroke-width: 2; stroke-linejoin: round; filter: drop-shadow(0 0 4px rgba(34, 211, 238, 0.4)); }
</style>

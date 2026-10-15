<script setup>
/* 判定/状态徽章：DONE 时看 resultCode（AC/WA/TLE/CE/RE），否则看流转状态 */
import { computed } from 'vue';

const props = defineProps({
  status: { type: String, default: '' },       // QUEUED | RUNNING | REQUEUE | DONE
  resultCode: { type: String, default: '' },   // AC | WA | TLE | CE | RE
});

const key = computed(() => {
  if (props.status === 'DONE') return props.resultCode || 'DONE';
  if (props.status === 'REQUEUE') return 'QUEUED';
  return props.status || 'DONE';
});
</script>

<template>
  <span class="badge" :class="key">{{ key }}</span>
</template>

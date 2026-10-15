import { createRouter, createWebHashHistory } from 'vue-router';
import ProblemsView from '../views/ProblemsView.vue';

// 三页签路由：题库 / 提交 / 监控
// 采用 hash 模式：dist/ 产物可被任意静态服务器（甚至 file://）直接托管，
// 无需服务端 SPA fallback 配置。
const routes = [
  { path: '/', redirect: '/problems' },
  { path: '/problems', name: 'problems', component: ProblemsView, meta: { title: '题库' } },
  { path: '/submit', name: 'submit', component: () => import('../views/SubmitView.vue'), meta: { title: '提交' } },
  { path: '/monitor', name: 'monitor', component: () => import('../views/MonitorView.vue'), meta: { title: '监控' } },
];

export default createRouter({
  history: createWebHashHistory(),
  routes,
});

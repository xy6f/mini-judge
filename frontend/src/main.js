import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';   // Element Plus 暗色主题
import './styles/tokens.css';                          // 深空设计令牌（后引入，覆盖 EP 默认观感）
import App from './App.vue';
import router from './router';

const app = createApp(App);
app.use(createPinia());   // Pinia 必须先于 router 注入：视图/拦截器里可能立即取 store
app.use(router);
app.use(ElementPlus);
app.mount('#app');

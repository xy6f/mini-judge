import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// mini-judge 前端构建配置
// 开发期所有 /api 请求经 Vite 代理转发到网关 8085（gateway 模块 local profile）。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5174,        // 固定端口，README/脚本中的地址不会漂移
    strictPort: true,
    proxy: {
      // 同源调用 /api/**：浏览器不带跨域负担，Authorization: Bearer <JWT> 原样透传；
      // 网关 JwtGlobalFilter 解析令牌后注入/覆盖 X-User-Id 再路由到
      // user(8101) / problem(8102) / judge(8103)——这就是网关注入 X-User-Id 的正规姿势。
      '/api': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
  },
});

/* API 调用流水 store：http.js 每次 fetch 记一条，日志抽屉渲染它 */
import { defineStore } from 'pinia';

export const useLogsStore = defineStore('logs', {
  state: () => ({
    /** [{t, method, path, http, code, ms, msg, bad}]，最新在前，上限 200 */
    entries: [],
    drawerOpen: false,
  }),
  getters: {
    count: (s) => s.entries.length,
  },
  actions: {
    push(entry) {
      this.entries.unshift(entry);
      if (this.entries.length > 200) this.entries.length = 200;
    },
    clear() { this.entries = []; },
    toggleDrawer(open) {
      this.drawerOpen = open === undefined ? !this.drawerOpen : open;
    },
  },
});

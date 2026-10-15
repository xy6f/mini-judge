/* 身份 store：token / userId / nickname / role，localStorage 持久化（键沿用 mj.*） */
import { defineStore } from 'pinia';
import { http } from '../api/http';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('mj.token') || null,
    userId: Number(localStorage.getItem('mj.userId')) || null,
    nickname: localStorage.getItem('mj.nickname') || '',
    role: localStorage.getItem('mj.role') || '',
  }),
  getters: {
    loggedIn: (s) => !!s.token,
  },
  actions: {
    setAuth(data) {
      this.token = data.token;
      this.userId = data.userId;
      this.nickname = data.nickname;
      this.role = data.role;
      localStorage.setItem('mj.token', data.token);
      localStorage.setItem('mj.userId', String(data.userId));
      localStorage.setItem('mj.nickname', data.nickname);
      localStorage.setItem('mj.role', data.role);
    },
    clearAuth() {
      this.token = null;
      this.userId = null;
      this.nickname = '';
      this.role = '';
      ['mj.token', 'mj.userId', 'mj.nickname', 'mj.role'].forEach((k) => localStorage.removeItem(k));
    },
    async login(username, password) {
      const data = await http.post('/api/auth/login', { username, password });
      this.setAuth(data);
      return data;
    },
    /**
     * 注册并登录：用户名已占用（业务 code 40901 / HTTP 409）时容错，直接走登录。
     * 刻意不带 adminBootstrapKey——普通入口注册出的账号就该是 USER，
     * 管理引导通道只保留给题库页的演示初始化（见 ProblemsView 注释）。
     */
    async registerAndLogin(username, password, nickname) {
      try {
        await http.post('/api/auth/register', { username, password, nickname: nickname || undefined });
      } catch (e) {
        if (e.code !== 40901 && e.http !== 409) throw e;
      }
      return this.login(username, password);
    },
  },
});

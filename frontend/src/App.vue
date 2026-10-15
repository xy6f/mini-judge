<script setup>
/* 应用壳：顶栏（品牌 / API 入口 / 身份徽章+登录浮层 / 日志按钮）+ 左导航 + 路由视图 + 日志抽屉 */
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore } from './stores/auth';
import { useLogsStore } from './stores/logs';
import LogDrawer from './components/LogDrawer.vue';

const auth = useAuthStore();
const logs = useLogsStore();
const router = useRouter();

const loginOpen = ref(false);
const identityOpen = ref(false);
const loginForm = reactive({ username: '', password: '', nickname: '' });
const loginBusy = ref(false);

async function doLogin() {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.warning('请填写用户名与密码');
    return;
  }
  loginBusy.value = true;
  try {
    const data = await auth.login(loginForm.username, loginForm.password);
    ElMessage.success(`欢迎回来，${data.nickname}`);
    loginOpen.value = false;
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loginBusy.value = false;
  }
}

async function doRegisterAndLogin() {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.warning('请填写用户名与密码');
    return;
  }
  loginBusy.value = true;
  try {
    const data = await auth.registerAndLogin(
      loginForm.username, loginForm.password, loginForm.nickname.trim());
    ElMessage.success(`注册并登录成功，欢迎 ${data.nickname}`);
    loginOpen.value = false;
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    loginBusy.value = false;
  }
}

function onIdentityCommand(cmd) {
  identityOpen.value = false;
  if (cmd === 'logout') {
    auth.clearAuth();
    ElMessage.success('已退出登录');
  }
}
</script>

<template>
  <header class="topbar glass">
    <div class="brand">
      <svg class="brand-logo" viewBox="0 0 24 24" fill="none" stroke="url(#bgrad)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <defs>
          <linearGradient id="bgrad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#8b5cf6" /><stop offset="1" stop-color="#22d3ee" />
          </linearGradient>
        </defs>
        <path d="M12 2 3 7v10l9 5 9-5V7z" />
        <path d="M8.5 10.5 6.5 12l2 1.5M15.5 10.5 17.5 12l-2 1.5M13.5 9.5l-3 5" />
      </svg>
      <span class="brand-name">mini-judge</span>
      <span class="brand-sep">·</span>
      <span class="brand-sub">评测控制台</span>
    </div>

    <div class="topbar-right">
      <span class="api-chip mono" title="开发期经 Vite proxy，生产期经反向代理，同路径转发到网关 8085">API · 网关 8085（/api 代理）</span>

      <!-- 身份徽章：未登录 → 登录浮层；已登录 → 下拉菜单 -->
      <template v-if="!auth.loggedIn">
        <el-popover v-model:visible="loginOpen" placement="bottom-end" :width="300" trigger="click" popper-class="login-pop">
          <template #reference>
            <button class="identity-btn">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5" />
              </svg>
              <span>登录</span>
            </button>
          </template>
          <div class="login-pop-body">
            <h3>登录 mini-judge</h3>
            <el-input v-model="loginForm.username" size="small" placeholder="如 demo" autocomplete="username" @keyup.enter="doLogin" />
            <el-input v-model="loginForm.password" size="small" type="password" show-password placeholder="至少 6 位" autocomplete="current-password" @keyup.enter="doLogin" />
            <el-input v-model="loginForm.nickname" size="small" placeholder="昵称（仅注册新账号时使用，可选）" @keyup.enter="doRegisterAndLogin" />
            <div class="login-actions">
              <button class="btn-gradient" :disabled="loginBusy" @click="doLogin">登录</button>
              <button class="ghost" :disabled="loginBusy" @click="doRegisterAndLogin">注册并登录</button>
            </div>
            <p class="login-hint muted">网关白名单只放行 <code class="mono">/api/auth/**</code>，其余接口登录后自动携带 Bearer 令牌。</p>
          </div>
        </el-popover>
      </template>
      <el-dropdown v-else trigger="click" @command="onIdentityCommand">
        <button class="identity-btn logged">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5" />
          </svg>
          <span>{{ auth.nickname || 'user' }} · {{ auth.role || 'USER' }}</span>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <button class="ghost-btn" title="API 调用日志" @click="logs.toggleDrawer()">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M4 6h16M4 12h10M4 18h7" /><circle cx="19" cy="16" r="3" /><path d="m21.4 18.4 1.6 1.6" />
        </svg>
        <span>API 日志</span>
        <span class="log-count">{{ logs.count }}</span>
      </button>
    </div>
  </header>

  <nav class="sidenav glass">
    <RouterLink v-for="r in [
      { to: '/problems', label: '题库', icon: 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20V4H6.5A2.5 2.5 0 0 0 4 6.5v13zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5' },
      { to: '/submit', label: '提交', icon: 'm10 13-5 5 2 2 5-5M14 6l5-5 5 5-5 5M9 14a5 5 0 0 1 7 0M15 10a5 5 0 0 1-7 0m-3 7 5 5 5-5' },
      { to: '/monitor', label: '监控', icon: 'M2 12h4l3-8 4 16 3-8h6' },
    ]" :key="r.to" :to="r.to" class="nav-btn" active-class="active">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path :d="r.icon" />
      </svg>
      <span>{{ r.label }}</span>
    </RouterLink>
  </nav>

  <main class="content">
    <RouterView />
  </main>

  <LogDrawer />
</template>

<style scoped>
.topbar {
  position: fixed; top: 14px; left: 14px; right: 14px; height: 56px; z-index: 60;
  display: flex; align-items: center; justify-content: space-between; padding: 0 18px;
}
.brand { display: flex; align-items: center; gap: 10px; }
.brand-logo { width: 26px; height: 26px; }
.brand-name {
  font-weight: 700; font-size: 17px; letter-spacing: 0.02em;
  background: var(--mj-grad); -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand-sep, .brand-sub { color: var(--mj-muted); font-size: 13px; }
.topbar-right { display: flex; align-items: center; gap: 10px; }

.api-chip {
  font-size: 11.5px; color: var(--mj-cyan); padding: 4px 10px;
  border: 1px solid rgba(34, 211, 238, 0.25); border-radius: 999px;
  background: rgba(34, 211, 238, 0.06);
}

.identity-btn {
  display: inline-flex; align-items: center; gap: 7px; cursor: pointer;
  padding: 7px 13px; border-radius: 999px; font-size: 13px;
  background: var(--mj-panel); border: 1px solid var(--mj-border); color: var(--mj-text);
  transition: all 0.15s;
}
.identity-btn:hover { border-color: var(--mj-border-hi); }
.identity-btn.logged { border-color: var(--mj-border-hi); background: var(--mj-grad-soft); }

.ghost-btn {
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer;
  padding: 7px 12px; border-radius: 8px; font-size: 13px; color: var(--mj-text);
  background: transparent; border: 1px solid var(--mj-border);
  transition: all 0.15s;
}
.ghost-btn:hover { border-color: var(--mj-border-hi); background: var(--mj-panel); }
.log-count {
  font-size: 11px; color: var(--mj-cyan); background: rgba(34, 211, 238, 0.1);
  border: 1px solid rgba(34, 211, 238, 0.3); border-radius: 999px; padding: 0 7px;
}

.login-pop-body h3 { margin: 0 0 10px; font-size: 14px; }
.login-pop-body :deep(.el-input) { margin-bottom: 8px; }
.login-actions { display: flex; gap: 8px; margin-top: 4px; }
.login-actions .btn-gradient { flex: 1; padding: 7px 0; font-size: 13px; }
.login-actions .ghost {
  flex: 1; padding: 7px 0; font-size: 13px; border-radius: 10px; cursor: pointer;
  background: var(--mj-panel-strong); border: 1px solid var(--mj-border); color: var(--mj-text);
}
.login-hint { margin: 10px 0 0; font-size: 11.5px; line-height: 1.6; }
.login-hint code { color: var(--mj-cyan); font-size: 11px; }

.sidenav {
  position: fixed; left: 14px; top: 82px; bottom: 14px; width: 76px; z-index: 50;
  display: flex; flex-direction: column; gap: 8px; padding: 12px 8px; align-items: stretch;
}
.nav-btn {
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 13px 4px; border-radius: 10px; font-size: 12.5px; text-decoration: none;
  color: var(--mj-muted); border: 1px solid transparent; transition: all 0.15s;
}
.nav-btn svg { width: 20px; height: 20px; }
.nav-btn:hover { color: var(--mj-text); background: var(--mj-panel); }
.nav-btn.active {
  color: #fff; background: var(--mj-grad-soft); border-color: var(--mj-border-hi);
  box-shadow: 0 0 18px rgba(139, 92, 246, 0.25);
}

.content { margin: 82px 14px 14px 104px; min-height: calc(100vh - 96px); }
@media (max-width: 900px) {
  .sidenav { flex-direction: row; top: auto; bottom: 0; left: 0; right: 0; width: auto; border-radius: 0; }
  .content { margin-bottom: 80px; }
  /* 窄屏下顶栏空间不足：隐藏说明性元素，避免品牌/徽章文字换行挤压 */
  .api-chip { display: none; }
  .brand-sub, .brand-sep { display: none; }
  .topbar { padding: 0 12px; }
  .topbar-right { gap: 8px; }
  .identity-btn, .ghost-btn { white-space: nowrap; }
}
</style>

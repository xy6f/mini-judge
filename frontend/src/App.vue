<script setup>
/* 应用壳：LeetCode 式顶部横向导航 —— 品牌 logo 左侧、题库/提交/监控居中 tab 式、
 * 用户徽章右侧 + 暗色切换（月亮/太阳）；移动端折为底部 tab。
 * 样式层重构（对标 LeetCode 中文版），业务逻辑与 API 不动 */
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

const NAV = [
  { to: '/problems', label: '题库' },
  { to: '/submit', label: '提交' },
  { to: '/monitor', label: '监控' },
];

/* ── 主题切换：亮色为主，保留暗色（月亮/太阳按钮） ── */
const isDark = ref(document.documentElement.classList.contains('dark'));
function toggleTheme() {
  isDark.value = !isDark.value;
  document.documentElement.classList.toggle('dark', isDark.value);
  try { localStorage.setItem('mj.theme', isDark.value ? 'dark' : 'light'); } catch { /* 私隐模式忽略 */ }
}
/* 启动时恢复上次选择（默认亮色） */
try {
  if (localStorage.getItem('mj.theme') === 'dark') {
    document.documentElement.classList.add('dark');
    isDark.value = true;
  } else {
    document.documentElement.classList.remove('dark');
  }
} catch { /* 忽略 */ }

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
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand">
        <svg class="brand-logo" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 2 3 7v10l9 5 9-5V7z" />
          <path d="M8.5 10.5 6.5 12l2 1.5M15.5 10.5 17.5 12l-2 1.5M13.5 9.5l-3 5" />
        </svg>
        <span class="brand-name">mini-judge</span>
        <span class="brand-sep">·</span>
        <span class="brand-sub">评测控制台</span>
      </div>

      <!-- 居中 tab 式横向导航（LeetCode 顶导航） -->
      <nav class="topnav">
        <RouterLink v-for="r in NAV" :key="r.to" :to="r.to" class="nav-tab" active-class="active">
          {{ r.label }}
        </RouterLink>
      </nav>

      <div class="topbar-right">
        <span class="api-chip mono" title="开发期经 Vite proxy，生产期经反向代理，同路径转发到网关 8085">API · 8085</span>

        <!-- 暗色切换：月亮 / 太阳 -->
        <button class="icon-btn" :title="isDark ? '切换亮色' : '切换暗色'" @click="toggleTheme">
          <svg v-if="isDark" viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="4.5" /><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11z" />
          </svg>
        </button>

        <!-- 身份徽章：未登录 → 登录浮层；已登录 → 下拉菜单 -->
        <template v-if="!auth.loggedIn">
          <el-popover v-model:visible="loginOpen" placement="bottom-end" :width="300" trigger="click" popper-class="login-pop">
            <template #reference>
              <button class="identity-btn">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
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
            <span class="avatar-chip">{{ (auth.nickname || 'u').slice(0, 1).toUpperCase() }}</span>
            <span class="identity-text">{{ auth.nickname || 'user' }} · {{ auth.role || 'USER' }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>

        <button class="icon-btn" title="API 调用日志" @click="logs.toggleDrawer()">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <path d="M4 6h16M4 12h10M4 18h7" /><circle cx="19" cy="16" r="3" /><path d="m21.4 18.4 1.6 1.6" />
          </svg>
          <span class="log-count mono">{{ logs.count }}</span>
        </button>
      </div>
    </div>
  </header>

  <main class="content">
    <RouterView />
  </main>

  <!-- 移动端底部 tab -->
  <nav class="bottomnav">
    <RouterLink v-for="r in NAV" :key="r.to" :to="r.to" class="bottom-tab" active-class="active">
      {{ r.label }}
    </RouterLink>
  </nav>

  <LogDrawer />
</template>

<style scoped>
.topbar {
  position: fixed; top: 0; left: 0; right: 0; height: 50px; z-index: 60;
  background: var(--mj-card);
  border-bottom: 1px solid var(--mj-border);
}
.topbar-inner {
  max-width: 1280px; height: 100%; margin: 0 auto; padding: 0 20px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.brand { display: flex; align-items: center; gap: 9px; color: var(--mj-text); }
.brand-logo { width: 24px; height: 24px; color: var(--mj-ac); }
.brand-name { font-weight: 700; font-size: 16px; letter-spacing: 0.01em; }
.brand-sep, .brand-sub { color: var(--mj-faint); font-size: 12.5px; }

/* 居中 tab：LeetCode 顶导航风 */
.topnav { display: flex; align-items: center; height: 100%; gap: 4px; }
.nav-tab {
  display: inline-flex; align-items: center; height: 50px; padding: 0 18px;
  font-size: 14px; text-decoration: none; color: var(--mj-muted);
  border-bottom: 2px solid transparent; transition: color 0.15s, border-color 0.15s;
  box-sizing: content-box; margin-bottom: -1px;
}
.nav-tab:hover { color: var(--mj-text); }
.nav-tab.active { color: var(--mj-text); font-weight: 600; border-bottom-color: var(--mj-ac); }

.topbar-right { display: flex; align-items: center; gap: 8px; }

.api-chip {
  font-size: 11.5px; color: var(--mj-muted); padding: 3px 10px;
  border: 1px solid var(--mj-border); border-radius: 999px;
  background: var(--mj-hover-bg);
}

.icon-btn {
  display: inline-flex; align-items: center; gap: 5px; cursor: pointer;
  padding: 7px; border-radius: var(--mj-radius); font-size: 13px;
  background: transparent; border: 1px solid transparent; color: var(--mj-muted);
  transition: all 0.15s;
}
.icon-btn:hover { color: var(--mj-text); background: var(--mj-hover-bg); }
.log-count {
  font-size: 11px; color: #fff; background: var(--mj-ac);
  border-radius: 999px; padding: 0 6px; line-height: 17px;
}

.identity-btn {
  display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
  padding: 5px 13px 5px 6px; border-radius: 999px; font-size: 13px;
  background: transparent; border: 1px solid var(--mj-border); color: var(--mj-text);
  transition: all 0.15s;
}
.identity-btn:hover { background: var(--mj-hover-bg); border-color: var(--mj-border-strong); }
.identity-btn.logged { border-color: rgba(38, 179, 92, 0.4); }
.avatar-chip {
  width: 26px; height: 26px; border-radius: 50%; display: inline-flex;
  align-items: center; justify-content: center; font-size: 13px; font-weight: 700;
  color: #fff; background: var(--mj-ac);
}
.identity-text { white-space: nowrap; }

.login-pop-body h3 { margin: 0 0 10px; font-size: 14px; }
.login-pop-body :deep(.el-input) { margin-bottom: 8px; }
.login-actions { display: flex; gap: 8px; margin-top: 4px; }
.login-actions .btn-gradient { flex: 1; padding: 7px 0; font-size: 13px; }
.login-actions .ghost {
  flex: 1; padding: 7px 0; font-size: 13px; border-radius: var(--mj-radius); cursor: pointer;
  background: var(--mj-hover-bg); border: 1px solid var(--mj-border); color: var(--mj-text);
}
.login-hint { margin: 10px 0 0; font-size: 11.5px; line-height: 1.6; }
.login-hint code { color: var(--mj-ac); font-size: 11px; }

.content { max-width: 1280px; margin: 0 auto; padding: 20px; min-height: calc(100vh - 50px); }

/* 移动端：顶部 tab 收起，折为底部 tab */
.bottomnav { display: none; }
@media (max-width: 900px) {
  .topnav { display: none; }
  .api-chip, .brand-sub, .brand-sep, .identity-text { display: none; }
  .identity-btn { padding: 5px 10px 5px 6px; }
  .content { padding: 14px 14px 76px; }
  .bottomnav {
    display: flex; position: fixed; left: 0; right: 0; bottom: 0; z-index: 60;
    height: 54px; background: var(--mj-card); border-top: 1px solid var(--mj-border);
  }
  .bottom-tab {
    flex: 1; display: flex; align-items: center; justify-content: center;
    font-size: 13px; text-decoration: none; color: var(--mj-faint);
    border-top: 2px solid transparent;
  }
  .bottom-tab.active { color: var(--mj-ac); font-weight: 600; border-top-color: var(--mj-ac); }
}
</style>

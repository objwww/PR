<template>
  <div class="appshell">
    <aside class="shell-side">
      <div class="logo">告警 RCA</div>
      <div v-for="g in navGroups" :key="g.title" class="nav-group">
        <div class="nav-group-title">{{ g.title }}</div>
        <template v-for="it in g.items" :key="it.label">
          <router-link :to="it.to" class="nav-it" :class="{ cur: isCur(it) }">
            {{ it.label }}
          </router-link>
          <router-link
            v-for="sub in it.children ?? []"
            :key="sub.label"
            :to="sub.to"
            class="nav-it sub"
            :class="{ cur: isCur(sub) }"
          >
            {{ sub.label }}
            <el-tag
              v-if="sub.badge"
              class="nav-badge"
              size="small"
              type="warning"
              effect="plain"
            >{{ sub.badge }}</el-tag>
          </router-link>
        </template>
      </div>
    </aside>
    <div class="shell-main">
      <header class="topbar">
        <span class="tb-left">
          <span class="env-badge">{{ envLabel }}</span>
        </span>
        <span class="tb-right">
          <span class="sse" :class="sse.status" :title="sseTitle">
            <i class="dot"></i>{{ sseText }}
          </span>
          <el-dropdown trigger="click" @command="onUserCommand">
            <span class="user-menu">
              {{ session.displayName }}<el-icon class="user-caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </span>
      </header>
      <main class="shell-content">
        <div class="content-inner">
          <router-view />
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session.js'
import { useSseStore } from '../stores/sseStatus.js'

// ⓪ 全局应用壳（UI-0 重做）：浅色侧栏分组导航 + 浅色顶栏（真环境徽章/真 SSE 指示/真用户菜单）
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const sse = useSseStore()

const navGroups = [
  {
    title: '工作台',
    items: [
      { label: '总览', to: '/overview', match: ['/overview'] },
      { label: '告警', to: '/alerts', match: ['/alerts', '/history'] },
      { label: '调查', to: '/runs', match: ['/runs'] },
      { label: '处置', to: '/cases', match: ['/cases'] },
    ],
  },
  {
    title: '运营',
    items: [
      {
        label: '值班', to: '/duty', match: ['/duty'],
        children: [
          { label: '通知预览', to: '/duty/chat', match: ['/duty/chat'], badge: '演练' },
        ],
      },
      { label: '通知', to: '/notifications', match: ['/notifications'] },
    ],
  },
  {
    title: '质量',
    items: [{ label: '评测', to: '/eval', match: ['/eval'] }],
  },
  {
    title: '系统',
    items: [{ label: '监控', to: '/monitor', match: ['/monitor'] }],
  },
]

function isCur(it) {
  // /duty/chat 是独立导航项（通知预览），不并入「值班」高亮
  if (route.path.startsWith('/duty/chat')) return it.match.includes('/duty/chat')
  return it.match.some(p => route.path === p || route.path.startsWith(p + '/'))
}

// 环境徽章：纯文本，读构建期注入的 VITE_ENV_LABEL，读不到如实显示「未标记」
const envLabel = import.meta.env.VITE_ENV_LABEL || '未标记'

const sseText = computed(() => ({
  connected: 'SSE 已连接',
  connecting: 'SSE 连接中',
  disconnected: 'SSE 未连接',
}[sse.status]))

const sseTitle = computed(() =>
  sse.lastEventAt ? `最后事件 ${sse.lastEventAt.toLocaleTimeString()}` : '尚无实时事件')

async function onUserCommand(cmd) {
  if (cmd !== 'logout') return
  await session.logout()
  router.replace('/login')
}
</script>

<style scoped>
.appshell { display: flex; min-height: 100vh; background: var(--bg); }

/* ---- 浅色侧栏 216px ---- */
.shell-side {
  width: 216px; flex: none;
  background: var(--card); border-right: 1px solid var(--line);
  padding: 16px 12px 12px;
  display: flex; flex-direction: column;
}
.logo {
  font-weight: 700; font-size: 16px; color: var(--head);
  padding: 0 10px 14px; letter-spacing: .5px;
}
.nav-group { margin-bottom: 16px; }
.nav-group-title {
  font-size: var(--fs-aux); color: var(--ink-2);
  padding: 0 10px 4px; letter-spacing: 1px;
}
.nav-it {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 12px; border-radius: var(--radius);
  font-size: 14px; margin-bottom: 2px; color: var(--ink); cursor: pointer;
}
.nav-it:hover { background: #f0f2f5; }
.nav-it.cur { background: var(--brand-soft); color: var(--brand); font-weight: 600; }
.nav-it.sub { padding-left: 24px; font-size: 13px; }
.nav-badge { margin-left: auto; }

.shell-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

/* ---- 浅色顶栏 ---- */
.topbar {
  background: var(--card); border-bottom: 1px solid var(--line);
  padding: 10px 24px; font-size: 13px; color: var(--ink-2);
  display: flex; justify-content: space-between; align-items: center;
  gap: 12px;
}
.env-badge {
  font-size: 12px; color: var(--ink-2);
  border: 1px solid var(--line); border-radius: var(--radius-ctl);
  padding: 2px 8px; background: var(--bg);
}

.sse { display: inline-flex; align-items: center; gap: 6px; }
.sse .dot { width: 8px; height: 8px; border-radius: 50%; }
.sse.connected .dot { background: var(--ok); animation: pulse 1.6s ease-in-out infinite; }
.sse.connecting .dot { background: var(--sev-p2); }
.sse.disconnected .dot { background: var(--sev-info); }
@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(35, 195, 67, .35); }
  50% { box-shadow: 0 0 0 4px rgba(35, 195, 67, 0); }
}

.tb-right { display: inline-flex; align-items: center; gap: 16px; }
.user-menu {
  display: inline-flex; align-items: center; gap: 4px;
  cursor: pointer; color: var(--ink); font-size: 13px; outline: none;
}
.user-menu:hover { color: var(--brand); }
.user-caret { font-size: 12px; }

.shell-content { flex: 1; min-width: 0; padding: var(--page-pad); }
.content-inner { max-width: 1440px; margin: 0 auto; }
</style>

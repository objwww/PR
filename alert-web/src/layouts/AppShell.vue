<template>
  <div class="appshell">
    <aside class="shell-side">
      <div class="logo">告警 RCA</div>
      <router-link
        v-for="it in nav"
        :key="it.label"
        :to="it.to"
        class="nav-it"
        :class="{ cur: isCur(it) }"
      >{{ it.label }}</router-link>
      <div class="nav-foot"><div class="nav-it disabled">⚙ 设置</div></div>
    </aside>
    <div class="shell-main">
      <header class="topbar">
        <span class="tb-left">
          <span class="env">生产环境 ▾</span>
          <span class="sep">｜</span>
          <span class="search">全局搜索
            <input class="search-input" type="text" placeholder="Incident / Run / Task / Trace" disabled>
          </span>
        </span>
        <span class="tb-right">
          <span class="sse">SSE <i class="dot"></i> 已连接</span>
          <span class="sep">｜</span>
          <span class="user">operator ▾</span>
        </span>
      </header>
      <main class="shell-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'

// ⓪ 全局应用壳（线框图 v1.6 #shell）：左侧深色导航 + 顶栏 + 内容区，P1–P6 共享
const route = useRoute()

const nav = [
  { label: '总览', to: '/overview', match: ['/overview'] },
  { label: '告警', to: '/alerts', match: ['/alerts', '/history'] },
  { label: '审查台', to: '/runs', match: ['/runs'] },
  { label: '处置', to: '/cases', match: ['/cases'] },
  { label: '评测', to: '/eval', match: ['/eval'] },
  { label: '监控', to: '/monitor', match: ['/monitor'] },
]

function isCur(it) {
  return it.match.some(p => route.path === p || route.path.startsWith(p + '/'))
}
</script>

<style scoped>
.appshell { display: flex; min-height: 100vh; background: var(--bg); }

.shell-side {
  width: 172px; flex: none;
  background: linear-gradient(180deg, #1c2733, #263449);
  color: #c7d2e0; padding: 12px 8px;
  display: flex; flex-direction: column;
}
.logo { color: #fff; font-weight: 700; font-size: 13px; padding: 4px 10px 14px; letter-spacing: .5px; }
.nav-it {
  display: block; padding: 7px 12px; border-radius: 8px;
  font-size: 12.5px; margin-bottom: 2px; color: #c7d2e0; cursor: pointer;
}
.nav-it:hover { background: rgba(255, 255, 255, .08); color: #fff; }
.nav-it.cur { background: var(--brand); color: #fff; font-weight: 600; }
.nav-it.disabled { cursor: default; opacity: .75; }
.nav-it.disabled:hover { background: none; color: #c7d2e0; }
.nav-foot { margin-top: auto; }

.shell-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.topbar {
  background: linear-gradient(135deg, #2b3b52, #3a4d68);
  color: #fff; padding: 9px 14px; font-size: 13px;
  display: flex; justify-content: space-between; align-items: center;
  flex-wrap: wrap; gap: 4px 12px;
}
.sep { color: #8fa1b8; margin: 0 8px; }
.env { cursor: pointer; }
.search { color: #d7e0ec; }
.search-input {
  margin-left: 6px; width: 220px; padding: 2px 10px;
  border: 1px solid rgba(255, 255, 255, .35); border-radius: 6px;
  background: rgba(255, 255, 255, .12); color: #fff; font-size: 12px;
}
.search-input::placeholder { color: #a9b8cb; }
.sse .dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: #7ee2a0; margin: 0 2px; vertical-align: middle;
}
.user { cursor: pointer; }

.shell-content { flex: 1; min-width: 0; padding: 14px; }
</style>

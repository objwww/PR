<template>
  <div class="eval-launch">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测中心<span class="sep">/</span>发起评测</div>
    <div class="lbl">新建实验：先冻结“测什么、怎么跑、怎么判”</div>

    <div class="card step"><b>① 数据</b><span class="step-body">订单域私有集 v3 / VALIDATION / 14 例 ｜ family 整组 ｜ rounds=2 ｜ seed=20260907</span></div>

    <div class="card step"><b>② 被测候选</b><span class="step-body">model=deepseek-v3 ｜ prompt=rca-v12 ｜ lexicon=v8 ｜ tool_registry=5d2… ｜ config=b92…</span></div>

    <div class="card step">
      <b>③ 回放级别</b>
      <select v-model="replay" class="replay-select">
        <option v-for="r in replayLevels" :key="r.key" :value="r.key">{{ r.nameZh }}</option>
      </select>
      <small class="step-note">Scorer Replay（只重算评分）/ Agent Replay（冻结观测重跑 Agent）/ Live E2E（真实注入+恢复，slot=1）/ Schema / Red-team——不同回放级别覆盖面不同，不把冻结观测回放的成绩冒充线上 E2E</small>
    </div>

    <div class="card step">
      <b>④ 评分套件</b><span class="step-body">Deterministic RCA v3 ｜ Schema v2 ｜ Safety v4 ｜ cost/latency spans</span>
      <small class="step-note">root_cause_hit 只读 EvidencePackageV2 类型化字段 + 版本化同义词白名单，不让自由文本或 LLM judge 决定 canonical 命中。</small>
    </div>

    <div class="card step"><b>⑤ 执行约束</b><span class="step-body">并发=1 ｜ timeout=20m ｜ retry=1 ｜ budget=¥10 ｜ 失败继续并落档 ｜ 上轮恢复+resolved 后才进下一轮</span></div>

    <div class="card step">
      <b>⑥ 预检</b><span class="step-body">权限 ✓ ｜ digest 齐全 ✓ ｜ family 泄漏 0 ✓ ｜ Live E2E slot ✓ ｜ 预计 42m / ¥6.8</span>
      <div class="step-actions">
        <button class="btn">保存草稿</button>
        <button class="btn primary" @click="launched = true">发起批量评测</button>
        <span v-if="launched" class="tag t-green">已入队（mock）：eval_run RUNNING，进度轮询</span>
      </div>
    </div>

    <div class="card note-card">
      <small>
        「发起批量评测」命令 API 后端未落码（须在任务拆解增补编号）：契约必备幂等键、并发配额、排队、终止状态、取消、RBAC、审计、执行指纹；
        TUNING / VALIDATION 可页面发起，HOLDOUT 只走「发布门」独立命令。第一期落地：四分区表未落库前，先只放开当前 eval-scenarios 注册表（5 场景枚举）。
      </small>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const replay = ref('AGENT_REPLAY')
const launched = ref(false)
const replayLevels = [
  { key: 'SCORER_REPLAY', nameZh: 'Scorer Replay' },
  { key: 'AGENT_REPLAY', nameZh: 'Agent Replay' },
  { key: 'LIVE_E2E', nameZh: 'Live E2E' },
  { key: 'SCHEMA', nameZh: 'Schema' },
  { key: 'RED_TEAM', nameZh: 'Red-team' },
]
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 8px; }

.step { padding: 10px 14px; margin-bottom: 8px; font-size: 13px; }
.step-body { margin-left: 10px; }
.step-note { display: block; color: var(--ink-2); margin-top: 4px; }
.replay-select {
  margin-left: 10px; padding: 2px 8px; font-size: 13px; font-family: inherit;
  border: 1px solid var(--line-strong); border-radius: 6px; background: #fff; color: var(--ink);
}
.step-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }

.note-card { padding: 10px 14px; color: var(--ink-2); }
</style>

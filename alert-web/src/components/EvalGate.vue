<template>
  <div class="eval-gate" v-if="g.qualityGate">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>发布门 🔒（独立命令与权限）</div>

    <div class="cols">
      <div class="col" style="flex:3">
        <div class="lbl">质量门</div>
        <div class="card box">
          <span class="tag" :class="g.qualityGate.passed ? 't-green' : 't-red'">
            {{ g.qualityGate.partition }} 质量门{{ g.qualityGate.passed ? '通过' : '未通过' }}
          </span>
          <div class="checks">
            <span v-for="ck in g.qualityGate.checks" :key="ck.name" class="check-item">
              {{ ck.name }} {{ ck.pass ? '✓' : '✕' }}<span v-if="ck.note" class="check-note">（{{ ck.note }}）</span>
            </span>
          </div>
        </div>

        <div class="lbl" style="margin-top:12px">发布审计</div>
        <div class="card box audit">
          <b>{{ g.audit.gateId }}</b> ｜ requested_by={{ g.audit.requestedBy }} ｜ config={{ g.audit.config }}
          ｜ HOLDOUT wear={{ g.audit.holdoutWear }} ｜ REDTEAM={{ g.audit.redteam }} ｜
          decision=<span class="tag t-red">{{ g.audit.decision }}</span> ｜ signed_at={{ g.audit.signedAt }}
        </div>
      </div>

      <div class="col" style="flex:2">
        <div class="lbl">HOLDOUT 发布门</div>
        <div class="card box">
          <button class="btn primary" @click="requested = true">{{ g.holdoutGate.action }}</button>
          <span v-if="requested" class="tag t-blue">已提交（mock）：带幂等键与审批原因</span>
          <small class="gate-note">{{ g.holdoutGate.requirements }}。{{ g.holdoutGate.redline }}。</small>
        </div>
        <div class="card box redline">
          <b>HOLDOUT 红线</b>：不参与调优、只用于发布门和对外数字；禁止进入 Agent/RAG/Prompt 调试/人工调优界面。
          与普通批量评测是<b>两个独立命令</b>；操作带幂等键 + 访问审计。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchGateStatus } from '../mocks/eval.js'

const g = reactive({ qualityGate: null, holdoutGate: {}, audit: {} })
const requested = ref(false)

onMounted(async () => {
  Object.assign(g, await api('/eval/gates', { mock: fetchGateStatus }))
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.cols { display: flex; gap: 10px; align-items: flex-start; }
.col { min-width: 0; }
.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }

.box { padding: 10px 14px; margin-bottom: 8px; font-size: 12.5px; }
.checks { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 4px 16px; }
.check-item { white-space: nowrap; }
.check-note { color: var(--ink-2); }

.gate-note { display: block; color: var(--ink-2); margin-top: 8px; }
.redline { border-left: 3px solid var(--sev-p0); color: var(--ink-2); }
.redline b { color: var(--ink); }
</style>

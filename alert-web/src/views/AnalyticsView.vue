<template>
  <div class="analytics-page">
    <header class="page-head">
      <div>
        <h1>数据分析</h1>
        <p class="sub">告警质量与 Agent 效能的七日趋势——数字全部来自真实账本聚合，可对账。</p>
      </div>
      <el-button @click="load" :loading="loading">刷新</el-button>
    </header>

    <el-alert v-if="loadFailed" type="error" show-icon :closable="false"
              title="趋势接口加载失败——卡片一律显示 —，不展示 0 冒充真实零值；请点刷新重试。"
              style="margin-bottom: 14px" />

    <el-row :gutter="14" class="cards">
      <el-col :span="6"><div class="card"><p class="k">七日接收告警</p><p class="v">{{ stat(total('alerts')) }}</p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">七日聚合事件</p><p class="v">{{ stat(total('incidents')) }}</p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">七日降噪率（加权）</p><p class="v">{{ stat(noiseReduction) }}<small v-if="!loadFailed && noiseReduction != null">%</small></p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">七日压缩比</p>
        <p class="v">{{ loadFailed ? '—' : (compression.ratio ?? '—') }}<small v-if="!loadFailed && compression.ratio != null"> : 1</small></p>
        <p class="s">{{ loadFailed ? '—' : `${compression.alerts ?? 0} 条告警 → ${compression.incidents ?? 0} 个事件` }}</p></div></el-col>
    </el-row>
    <el-row :gutter="14" class="cards">
      <el-col :span="6"><div class="card"><p class="k">AI 结论采纳率</p><p class="v">{{ loadFailed ? '—' : (acceptance.rate ?? '—') }}<small v-if="!loadFailed && acceptance.rate != null">%</small></p>
        <p class="s">{{ loadFailed ? '—' : `确认 ${acceptance.confirmed ?? 0} / 标注 ${acceptance.total ?? 0}` }}</p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">AI 结论误报率</p><p class="v">{{ loadFailed ? '—' : (acceptance.falsePositiveRate ?? '—') }}<small v-if="!loadFailed && acceptance.falsePositiveRate != null">%</small></p>
        <p class="s">{{ loadFailed ? '—' : `驳回 ${acceptance.rejected ?? 0} / 标注 ${acceptance.total ?? 0}` }}</p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">七日静默抑制通知</p><p class="v">{{ loadFailed ? '—' : (silence.suppressedTotal ?? 0) }}</p>
        <p class="s">{{ loadFailed ? '—' : `生效静默规则 ${silence.rulesActive ?? 0} / ${silence.rulesTotal ?? 0}` }}</p></div></el-col>
      <el-col :span="6"><div class="card"><p class="k">七日完成调查</p><p class="v">{{ stat(total('runsDone')) }}</p></div></el-col>
    </el-row>

    <div class="table-wrap">
      <el-table :data="days" stripe>
        <el-table-column prop="day" label="日期" width="110" />
        <el-table-column prop="alerts" label="接收告警" align="right" />
        <el-table-column prop="incidents" label="聚合事件" align="right" />
        <el-table-column prop="runsDone" label="完成调查" align="right" />
        <el-table-column prop="suppressed" label="静默抑制" align="right" />
        <el-table-column label="告警→事件转化率" align="right">
          <template #default="{ row }">{{ row.conversionRate ?? '—' }}<small v-if="row.conversionRate != null">%</small></template>
        </el-table-column>
        <el-table-column label="降噪率" align="right">
          <template #default="{ row }">{{ row.noiseReduction ?? '—' }}<small v-if="row.noiseReduction != null">%</small></template>
        </el-table-column>
        <el-table-column label="压缩比" align="right">
          <template #default="{ row }">{{ row.compressionRatio ?? '—' }}<small v-if="row.compressionRatio != null"> : 1</small></template>
        </el-table-column>
      </el-table>
      <p class="note">口径：降噪率 = 1 − 事件数 ÷ 接收告警数；压缩比 = 接收告警数 ÷ 聚合事件数（GRAL 口径）；误报率 = 人工驳回（REJECTED）÷ 全部已判定标注（告警详情页「确认/驳回 AI 结论」产生标注）；静默抑制 = 控制面路由 SUPPRESSED 决策数（真链见 alert_inbox），生效静默规则数来自 notify_silence 配置面——命中面与配置面分开陈述，不互相冒充。</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'

const loading = ref(false)
const loadFailed = ref(false)
const days = ref([])
const acceptance = ref({})
const silence = ref({})
const compression = ref({})

function total(key) {
  return days.value.reduce((s, d) => s + (d[key] ?? 0), 0)
}
// 失败态不展示数字（防 0 冒充真实零值）；真实零值照常展示
function stat(v) {
  return loadFailed.value ? '—' : v
}
// 七日加权降噪率 = 1 − 七日事件总量 ÷ 七日告警总量（与下方口径声明一致；
// 日均算术平均会让小流量日同等权重扭曲结果，弃用）
const noiseReduction = computed(() => {
  const alerts = total('alerts')
  if (!alerts) return null
  return Math.round((1 - total('incidents') / alerts) * 1000) / 10
})

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const d = await api('/v1/analytics/trends')
    days.value = d?.days ?? []
    acceptance.value = d?.aiAcceptance ?? {}
    silence.value = d?.silence ?? {}
    compression.value = d?.compression ?? {}
  } catch (e) {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<style scoped>
.analytics-page { max-width: 1280px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h1 { font-size: 24px; margin: 0 0 6px; }
.page-head .sub { color: #5f6b7a; font-size: 13px; margin: 0; }
.cards { margin-bottom: 14px; }
.card { background: #fff; border: 1px solid #d8dee4; border-radius: 8px; padding: 14px 16px; }
.card .k { font-size: 12px; color: #5f6b7a; margin: 0 0 6px; }
.card .v { font-size: 26px; font-weight: 700; margin: 0; }
.card .v small { font-size: 13px; font-weight: 400; }
.card .s { font-size: 12px; color: #5f6b7a; margin: 4px 0 0; }
.table-wrap { background: #fff; border: 1px solid #d8dee4; border-radius: 8px; padding: 14px 16px; }
.note { font-size: 12px; color: #5f6b7a; margin: 10px 2px 0; line-height: 1.7; }
</style>

<template>
  <div>
    <PageHeader
      title="配置中心"
      subtitle="告警分类 / 接入管理 / 通知渠道 / 权限与角色 / 调查路由——全部读面直连既有真表与部署装配；密钥原文不出环境变量"
    />

    <div class="card panel">
      <el-tabs v-model="tab">
        <!-- ============ 告警分类 ============ -->
        <el-tab-pane label="告警分类" name="category">
          <div class="hint-row">
            每条告警进来时按规则自动归类（应用/基础设施等）；归不进任何规则的就是「未分类」——未分类占比 = 分类规则覆盖度的诚实指标。
            想降低它：在告警详情页逐条人工修正（立即生效），或让开发扩充分类规则。
          </div>
          <template v-if="catStats">
            <div class="cfg-stats">
              <span>事件总数 <b>{{ fmtNum(catStats.total) }}</b></span>
              <span>人工修正 <b>{{ fmtNum(catStats.overridden) }}</b></span>
              <span>未分类 <b>{{ fmtNum(catStats.unclassified) }}</b>（{{ catStats.unclassifiedRate ?? '—' }}%）</span>
            </div>
            <el-table :data="catStats.items" size="small">
              <el-table-column label="分类" min-width="140">
                <template #default="{ row }">{{ catZh(row.category) }}</template>
              </el-table-column>
              <el-table-column label="事件数" width="110" align="right">
                <template #default="{ row }">{{ fmtNum(row.total) }}</template>
              </el-table-column>
              <el-table-column label="规则判定" width="110" align="right">
                <template #default="{ row }">{{ fmtNum(row.rule) }}</template>
              </el-table-column>
              <el-table-column label="人工修正" width="110" align="right">
                <template #default="{ row }">{{ fmtNum(row.override) }}</template>
              </el-table-column>
              <el-table-column label="占比" min-width="220">
                <template #default="{ row }">
                  <div class="bar-wrap">
                    <div class="bar" :style="{ width: barPct(row.total) + '%' }" />
                    <span class="bar-num">{{ barPct(row.total) }}%</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="!catStats.items.length" class="empty-note">
              暂无事件——分类分布来自 incident 表实时聚合，事件接入后自动出数。
            </div>
          </template>
        </el-tab-pane>

        <!-- ============ 接入管理 ============ -->
        <el-tab-pane label="接入管理" name="intake">
          <div class="hint-row">
            告警入口：Alertmanager webhook（机器兼容：无签名走 bearer 链；带 X-PA-Signature 则强制
            HMAC-SHA256 验签，失败即 401 不回落）。密钥原文只存在于部署环境变量，此处仅展示 keyId 与指纹。
          </div>
          <template v-if="intake">
            <div class="zone-head">
              <h3 class="sec-title">Webhook 接入</h3>
              <el-tag size="small" :type="intake.hmacEnabled ? 'success' : 'warning'" disable-transitions>
                {{ intake.hmacEnabled ? 'HMAC 已启用' : 'HMAC 未配置（bearer-only 姿态）' }}
              </el-tag>
            </div>
            <div class="kv-line"><span class="k">接入路径</span><code>{{ intake.webhookPath }}</code></div>
            <div class="kv-line"><span class="k">密钥 keyId</span>
              <span v-if="intake.hmacKeyIds.length">
                <code v-for="id in intake.hmacKeyIds" :key="id" class="keyid">{{ id }}</code>
              </span>
              <span v-else class="dim">—（在部署 .env 的 app.alert.webhook.hmac-keys 配置，格式 keyId:secret）</span>
            </div>
            <div class="kv-line"><span class="k">姿态说明</span><span class="dim">{{ intake.hmacNote }}</span></div>

            <div class="zone-head" style="margin-top: 18px">
              <h3 class="sec-title">机器线凭证</h3>
              <span class="dim">已配置 = 环境变量有值（指纹为 SHA-256 前 8 位，不可逆）</span>
            </div>
            <el-table :data="intake.machineLines" size="small">
              <el-table-column prop="name" label="线名" min-width="170" />
              <el-table-column label="铸定角色" width="180">
                <template #default="{ row }"><code class="role-code">{{ row.role }}</code></template>
              </el-table-column>
              <el-table-column prop="property" label="环境变量" min-width="260" show-overflow-tooltip />
              <el-table-column label="状态" width="220">
                <template #default="{ row }">
                  <el-tag v-if="row.configured" type="success" size="small" disable-transitions>
                    已配置 · 指纹 {{ row.fingerprint }}
                  </el-tag>
                  <el-tag v-else type="info" size="small" disable-transitions>未配置</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </el-tab-pane>

        <!-- ============ 通知渠道 ============ -->
        <el-tab-pane label="通知渠道" name="channels">
          <div class="hint-row">
            渠道按优先级投递（每优先级一条，兜底通道恰一条）；URL/密钥只配在部署环境变量。完整读写面在
            <router-link to="/duty">值班管理</router-link>；此处为配置视角的清单与启停。
          </div>
          <div class="zone-head">
            <h3 class="sec-title">通知通道</h3>
            <el-button size="small" :loading="testLoading" @click="sendTest">发送测试通知</el-button>
            <span v-if="testResult" class="dim">测试派发：{{ testResult }}</span>
          </div>
          <el-table :data="channels" size="small">
            <el-table-column prop="name" label="名称" min-width="150" />
            <el-table-column prop="platform" label="平台" width="110" />
            <el-table-column label="优先级" width="90">
              <template #default="{ row }">P{{ row.priority }}</template>
            </el-table-column>
            <el-table-column label="兜底" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.isFallback" type="warning" size="small">兜底</el-tag>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="envKeyWebhook" label="webhook 环境变量" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small" disable-transitions>
                  {{ row.enabled ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="right">
              <template #default="{ row }">
                <el-button size="small" :type="row.enabled ? 'warning' : 'success'" plain @click="toggleChannel(row)">
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty><span class="dim">暂无通道——通道为空时通知只落台账不投递</span></template>
          </el-table>
        </el-tab-pane>

        <!-- ============ 权限与角色 ============ -->
        <el-tab-pane label="权限与角色" name="users">
          <div class="hint-row">
            平台账号与机器线两类主体：账号管理（新增/口令/启停）在
            <router-link to="/users">账号管理页</router-link>；机器线凭证在「接入管理」页签。
          </div>
          <el-table :data="users" size="small">
            <el-table-column prop="username" label="账号" min-width="160" />
            <el-table-column label="角色" width="160">
              <template #default="{ row }"><code class="role-code">{{ row.role ?? 'OPERATOR' }}</code></template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="row.active === false ? 'info' : 'success'" size="small" disable-transitions>
                  {{ row.active === false ? '已停用' : '启用' }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty><span class="dim">账号清单加载失败或为空——完整读写面见账号管理页</span></template>
          </el-table>
        </el-tab-pane>

        <!-- ============ 调查路由（回答"有告警为什么没有 RCA"） ============ -->
        <el-tab-pane label="调查路由" name="routing">
          <div class="hint-row">
            AI 自动调查走 canary 粘性桶位路由：告警键哈希桶 vs 当前放量百分比，每条决策全账本落账。
            放量百分比由发布管线的 bundle 激活驱动（版本中心），告警域不设开关——本页为决策账本的只读可视化。
            「桶内·降级观测」= 桶位在放量内但原生执行面未就绪，降级观测不铸调查（如实分态）。
          </div>
          <template v-if="routing">
            <h3 class="sec-title">等待放量的事件（尚未自动 RCA 与原因）</h3>
            <el-table :data="routing.waiting" size="small" style="margin-bottom: 16px">
              <el-table-column label="告警名" min-width="180">
                <template #default="{ row }">{{ row.alertname ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="服务" min-width="140">
                <template #default="{ row }">{{ row.service ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="等待原因" width="150">
                <template #default="{ row }">
                  <el-tag size="small" type="warning" disable-transitions>
                    {{ WAITING_REASON_ZH[row.waitingReason] ?? row.waitingReason }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="首次发生" width="160">
                <template #default="{ row }">{{ fmtTime(row.firstSeenAt) }}</template>
              </el-table-column>
              <el-table-column label="接收" width="80" align="right">
                <template #default="{ row }">{{ row.receivedCount }}</template>
              </el-table-column>
              <el-table-column label="操作" width="110">
                <template #default="{ row }">
                  <router-link :to="`/alerts/${row.incidentId}`">
                    <el-button size="small" plain>打开详情</el-button>
                  </router-link>
                </template>
              </el-table-column>
              <template #empty><span class="dim">当前无等待放量的事件——告警事件均在自动调查覆盖内</span></template>
            </el-table>

            <h3 class="sec-title">每告警键最新放量状态</h3>
            <el-table :data="routing.latest" size="small" style="margin-bottom: 16px" max-height="300">
              <el-table-column prop="key" label="告警键" min-width="280" show-overflow-tooltip />
              <el-table-column label="放量" width="90" align="right">
                <template #default="{ row }">{{ row.percent }}%</template>
              </el-table-column>
              <el-table-column label="最新决策" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="decTag(row.decision)" disable-transitions>{{ decZh(row.decision) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="决策时间" width="160">
                <template #default="{ row }">{{ fmtTime(row.at) }}</template>
              </el-table-column>
            </el-table>

            <h3 class="sec-title">最近决策流水（最新 20 条）</h3>
            <el-table :data="routing.recent" size="small" max-height="300">
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ fmtTime(row.at) }}</template>
              </el-table-column>
              <el-table-column prop="key" label="告警键" min-width="260" show-overflow-tooltip />
              <el-table-column label="桶位" width="80" align="right">
                <template #default="{ row }">{{ row.bucket }}</template>
              </el-table-column>
              <el-table-column label="放量" width="80" align="right">
                <template #default="{ row }">{{ row.percent }}%</template>
              </el-table-column>
              <el-table-column label="决策" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="decTag(row.decision)" disable-transitions>{{ decZh(row.decision) }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>
          <div v-else class="empty-note">路由决策账本加载失败或为空（canary_route_decision）。</div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
// 3.6 配置中心（前端产品化 Wave 5）：四页签聚合既有真面——
//   告警分类 = incident.category 生成列实时聚合（新读面 /api/v1/config/category-stats）；
//   接入管理 = /api/v1/config/intake（与 SecurityConfig 装配同源的配置状态，指纹不回显原文）；
//   通知渠道 = /api/duty/channels 既有读写面（启停+测试发送）；
//   权限与角色 = /api/auth/users 清单（完整读写面在 /users 账号管理页，不重复建设）。
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session.js'
import { CATEGORY_ZH, ROUTE_DECISION_ZH, WAITING_REASON_ZH } from '../dict/zh.js'
import { fmtTime } from '../utils/format'
import PageHeader from '../components/common/PageHeader.vue'

const session = useSessionStore()
const tab = ref('category')

const catStats = ref(null)
const intake = ref(null)
const channels = ref([])
const users = ref([])
const routing = ref(null)
const testLoading = ref(false)
const testResult = ref('')

function catZh(c) { return CATEGORY_ZH[c] ?? c }
function decZh(d) { return ROUTE_DECISION_ZH[d] ?? d }
function decTag(d) {
  return { BUCKETED_NATIVE: 'success', WHITELISTED: 'primary', BUCKETED_HOLMES: 'warning', CANARY_DISABLED: 'info' }[d] ?? 'info'
}
function fmtNum(n) { return n == null ? '—' : Number(n).toLocaleString('zh-CN') }
// 分类占比条（修复既有缺陷：模板引用但函数缺失，渲染即 TypeError）
function barPct(total) {
  const t = Number(catStats.value?.total ?? 0)
  if (!t || total == null) return 0
  return Math.round(Number(total) * 1000 / t) / 10
}

async function loadCategory() {
  try {
    const res = await api('/v1/config/category-stats')
    if (res?.status === 'OK') catStats.value = res
  } catch { /* 分类面缺席如实留空 */ }
}
async function loadIntake() {
  try {
    const res = await api('/v1/config/intake')
    if (res?.status === 'OK') intake.value = res
  } catch { /* 接入面缺席如实留空 */ }
}
async function loadChannels() {
  try {
    const cs = await api('/duty/channels')
    channels.value = Array.isArray(cs) ? cs : (cs?.items ?? [])
  } catch { channels.value = [] }
}
async function toggleChannel(row) {
  try {
    await api(`/duty/channels/${row.id}`, { method: 'PUT', body: { enabled: !row.enabled } })
    row.enabled = !row.enabled
  } catch { ElMessage.error('启停失败，请重试') }
}
async function sendTest() {
  testLoading.value = true
  try {
    const res = await api('/duty/test-notification', {
      method: 'POST',
      body: { title: '配置中心测试通知', body: '由配置中心发起的通道测试', severity: null },
    })
    testResult.value = res?.ok || res ? '已派发（投递结果见投递台账）' : '已提交'
  } catch { testResult.value = '' ; ElMessage.error('测试派发失败') } finally { testLoading.value = false }
}
async function loadUsers() {
  try {
    const res = await api('/auth/users')
    users.value = Array.isArray(res) ? res : (res?.items ?? [])
  } catch { users.value = [] }
}
async function loadRouting() {
  try {
    const res = await api('/v1/routing/overview')
    if (res?.status === 'OK') routing.value = res
  } catch { /* 路由账本缺席如实留空 */ }
}

onMounted(() => { loadCategory(); loadIntake(); loadChannels(); loadUsers(); loadRouting() })
</script>

<script>
export default { name: 'ConfigCenterView' }
</script>

<style scoped>
.hint-row { color: var(--ink-2); font-size: var(--fs-aux); line-height: 1.8; margin-bottom: 12px; }
.cfg-stats { display: flex; gap: 20px; margin-bottom: 10px; color: var(--ink-2); font-size: var(--fs-aux); }
.cfg-stats b { color: var(--ink); }
.zone-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 6px 0 10px; }
.sec-title {
  font-size: var(--fs-section); font-weight: 600; color: var(--head);
  padding-left: 9px; border-left: 3px solid var(--brand); margin: 14px 0 8px;
}
.kv-line { display: flex; gap: 10px; align-items: baseline; padding: 4px 0; font-size: var(--fs-body); }
.kv-line .k { flex: 0 0 90px; color: var(--ink-2); font-size: var(--fs-aux); }
.keyid { margin-right: 6px; }
.role-code { font-size: var(--fs-aux); }
.dim { color: var(--ink-2); font-size: var(--fs-aux); }
.bar-wrap { display: flex; align-items: center; gap: 8px; }
.bar { height: 8px; background: var(--brand); border-radius: 4px; min-width: 2px; }
.bar-num { font-size: var(--fs-aux); color: var(--ink-2); }
.empty-note { color: var(--ink-2); font-size: var(--fs-aux); padding: 10px 0; }
</style>

<template>
  <div class="duty-page">
    <PageHeader title="值班管理" subtitle="当前值班、未来七天排班与通知通道">
      <template #actions>
        <el-button :loading="loading" @click="reload">刷新快照</el-button>
      </template>
    </PageHeader>

    <!-- 当班横幅卡：快照解析（与 127 adapter 同源）；快照过期警告只显示在当班区域 -->
    <div class="card banner">
      <template v-if="snap">
        <div class="banner-main">
          <div class="banner-who">
            <span class="banner-label">当前当班</span>
            <b class="banner-name">{{ snap.onCall || '（空排班，走 fallback 通道）' }}</b>
            <el-tag v-if="snap.viaOverride" type="warning" size="small">临时替班</el-tag>
            <el-tag v-if="snap.viaFallback" type="danger" size="small">fallback 通道</el-tag>
          </div>
          <div class="banner-meta">
            <div class="bm-row">
              <span class="bm-k">通道链</span>
              <span>{{ snap.channels?.length ? snap.channels.map(c => `${c.name}(P${c.priority})`).join(' → ') : '—' }}</span>
            </div>
            <div class="bm-row">
              <span class="bm-k">升级链</span>
              <span>{{ snap.escalationChain?.length ? snap.escalationChain.join(' → ') : '—' }}</span>
            </div>
            <div class="bm-row">
              <span class="bm-k">快照</span>
              <span>v{{ snap.scheduleVersion }} ｜ 生成于 {{ fmtTime(snap.generatedAt) }} ｜ 有效期至 {{ fmtTime(snap.validUntil) }}</span>
            </div>
          </div>
        </div>
        <el-alert
          v-if="stale" type="warning" :closable="false" class="stale-alert"
          :title="`快照已过有效期（${fmtTime(snap.validUntil)}）——展示值可能陈旧，刷新后以新快照为准`"
        />
      </template>
      <el-alert
        v-else type="info" :closable="false"
        title="尚无排班快照——首启无排班时派发只走 fallback 通道"
      />
    </div>

    <!-- 未来七天排班：按排班参数（轮换/锚点/交接时间/层 0 成员/替班窗口）前端推算 -->
    <div class="card week-card">
      <div class="sec-title">
        未来七天排班
        <span class="sec-hint">按排班参数推算，以快照解析为准</span>
      </div>
      <template v-if="schedule?.exists">
        <div class="week-grid">
          <div v-for="d in weekDays" :key="d.key" class="day-cell" :class="{ today: d.isToday }">
            <div class="day-head">{{ d.weekLabel }}</div>
            <div class="day-date">{{ d.dateLabel }}</div>
            <div class="day-who">
              <template v-if="d.member">
                <b>{{ d.member }}</b>
                <el-tag v-if="d.viaOverride" type="warning" size="small">替班</el-tag>
              </template>
              <span v-else class="day-none">—</span>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="week-empty">
        <span>暂无排班——先到「排班」页签完成创建后，此处展示未来七天当班人。</span>
        <el-button size="small" type="primary" plain @click="tab = 'schedule'">前往排班</el-button>
      </div>
    </div>

    <!-- 二级页签：排班 / 成员 / 通知通道 -->
    <div class="card admin-card">
      <el-tabs v-model="tab">
        <!-- ============ 排班 ============ -->
        <el-tab-pane label="排班" name="schedule">
          <template v-if="schedule?.exists">
            <div class="sched-block">
              <div class="blk-head">
                <span class="blk-title">排班参数</span>
                <el-button size="small" @click="openScheduleDialog">编辑参数</el-button>
              </div>
              <el-descriptions :column="3" border size="small">
                <el-descriptions-item label="名称">{{ schedule.schedule.name || '—' }}</el-descriptions-item>
                <el-descriptions-item label="轮换">{{ rotationLabel(schedule.schedule.rotation) }}</el-descriptions-item>
                <el-descriptions-item label="时区">{{ schedule.schedule.timezone || '—' }}</el-descriptions-item>
                <el-descriptions-item label="锚点日期">{{ schedule.schedule.anchorDate || '—' }}</el-descriptions-item>
                <el-descriptions-item label="交接时间">{{ schedule.schedule.handoffTime || '—' }}</el-descriptions-item>
                <el-descriptions-item label="排班版本">v{{ snap?.scheduleVersion ?? '—' }}</el-descriptions-item>
              </el-descriptions>
            </div>

            <div class="sched-block">
              <div class="blk-head">
                <span class="blk-title">轮值层<span class="blk-hint">0 层为当班层，序号即升级顺序</span></span>
                <el-button size="small" type="primary" plain :disabled="!activeMembers.length" @click="layerDialog = true">追加层</el-button>
              </div>
              <el-table :data="layers" size="small">
                <el-table-column label="层" width="80">
                  <template #default="{ row }">层 {{ row.layerIndex }}</template>
                </el-table-column>
                <el-table-column label="成员">
                  <template #default="{ row }">{{ row.memberIds.map(memberName).join(' / ') || '—' }}</template>
                </el-table-column>
                <el-table-column label="操作" width="100" align="right">
                  <template #default="{ row }">
                    <el-popconfirm title="删除该轮值层？" @confirm="removeLayer(row)">
                      <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
                <template #empty><span class="muted">暂无轮值层——追加层后快照才会解析出当班人</span></template>
              </el-table>
            </div>

            <div class="sched-block">
              <div class="blk-head">
                <span class="blk-title">临时替班<span class="blk-hint">指定窗口内顶替当班人</span></span>
                <el-button size="small" type="primary" plain :disabled="!activeMembers.length" @click="overrideDialog = true">添加替班</el-button>
              </div>
              <el-table :data="overrides" size="small">
                <el-table-column label="替班人" width="140">
                  <template #default="{ row }">{{ memberName(row.memberId) }}</template>
                </el-table-column>
                <el-table-column label="开始"><template #default="{ row }">{{ fmtTime(row.startsAt) }}</template></el-table-column>
                <el-table-column label="结束"><template #default="{ row }">{{ fmtTime(row.endsAt) }}</template></el-table-column>
                <el-table-column label="事由" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.reason || '—' }}</template>
                </el-table-column>
                <el-table-column label="操作" width="100" align="right">
                  <template #default="{ row }">
                    <el-popconfirm title="删除该替班？" @confirm="removeOverride(row)">
                      <template #reference><el-button size="small" type="danger" plain>删除</el-button></template>
                    </el-popconfirm>
                  </template>
                </el-table-column>
                <template #empty><span class="muted">暂无替班</span></template>
              </el-table>
            </div>
          </template>
          <!-- 首启创建引导：当前 API 未开放首行排班创建（仅 PUT 更新既有排班），引导但不伪造入口 -->
          <EmptyState v-else kind="empty" description="暂无排班">
            <div class="create-guide">
              <p>首行排班需由管理员在服务端创建（当前接口未开放首启创建，防止误建多排班）。</p>
              <p>创建完成后回到本页维护排班参数、轮值层与临时替班。</p>
              <el-button :loading="loading" @click="reload">刷新检查</el-button>
            </div>
          </EmptyState>
        </el-tab-pane>

        <!-- ============ 成员 ============ -->
        <el-tab-pane label="成员" name="members">
          <div class="blk-head">
            <span class="blk-title">值班成员<span class="blk-hint">停用为软删除，不影响历史记录</span></span>
            <el-button size="small" type="primary" plain @click="memberDialog = true">添加成员</el-button>
          </div>
          <el-table :data="members" size="small">
            <el-table-column prop="name" label="账号名" min-width="140" />
            <el-table-column label="显示名" min-width="140">
              <template #default="{ row }">{{ row.displayName || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '在册' : '已停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="right">
              <template #default="{ row }">
                <el-button size="small" :type="row.active ? 'warning' : 'success'" plain @click="toggleMember(row)">
                  {{ row.active ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty><span class="muted">暂无成员——先添加成员再配置轮值层</span></template>
          </el-table>
        </el-tab-pane>

        <!-- ============ 通知通道 ============ -->
        <el-tab-pane label="通知通道" name="channels">
          <div class="blk-head">
            <span class="blk-title">通知通道<span class="blk-hint">每优先级一条；fallback 恰一条；URL/密钥只配在部署环境变量</span></span>
            <el-button size="small" type="primary" plain @click="channelDialog = true">添加通道</el-button>
          </div>
          <el-table :data="channels" size="small">
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="platform" label="平台" width="110" />
            <el-table-column label="优先级" width="90">
              <template #default="{ row }">P{{ row.priority }}</template>
            </el-table-column>
            <el-table-column label="fallback" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.isFallback" type="warning" size="small">fallback</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="envKeyWebhook" label="webhook 环境变量" min-width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170" align="right">
              <template #default="{ row }">
                <el-button size="small" @click="openChannelDetail(row)">详情</el-button>
                <el-button size="small" :type="row.enabled ? 'warning' : 'success'" plain @click="toggleChannel(row)">
                  {{ row.enabled ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty><span class="muted">暂无通道——通道为空时通知只落台账不投递</span></template>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 排班参数编辑弹窗 -->
    <el-dialog v-model="schedDialog" title="编辑排班参数" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model.trim="schedForm.name" /></el-form-item>
        <el-form-item label="轮换">
          <el-select v-model="schedForm.rotation" style="width: 100%">
            <el-option value="DAILY" label="按天轮换" />
            <el-option value="WEEKLY" label="按周轮换" />
          </el-select>
        </el-form-item>
        <el-form-item label="时区"><el-input v-model.trim="schedForm.timezone" placeholder="Asia/Shanghai" /></el-form-item>
        <el-form-item label="锚点日期"><el-input v-model.trim="schedForm.anchorDate" placeholder="2026-09-07" /></el-form-item>
        <el-form-item label="交接时间"><el-input v-model.trim="schedForm.handoffTime" placeholder="09:00" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="schedDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSchedule">保存（递增排班版本）</el-button>
      </template>
    </el-dialog>

    <!-- 追加轮值层弹窗 -->
    <el-dialog v-model="layerDialog" title="追加轮值层" width="420px">
      <el-form label-width="90px">
        <el-form-item label="成员">
          <el-select v-model="layerForm.memberIds" multiple style="width: 100%" placeholder="选择本层成员（可多选）">
            <el-option v-for="m in activeMembers" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="layerDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!layerForm.memberIds.length" :loading="saving" @click="addLayer">追加</el-button>
      </template>
    </el-dialog>

    <!-- 添加替班弹窗 -->
    <el-dialog v-model="overrideDialog" title="添加临时替班" width="480px">
      <el-form label-width="90px">
        <el-form-item label="替班人">
          <el-select v-model="overrideForm.memberId" style="width: 100%" placeholder="选择成员">
            <el-option v-for="m in activeMembers" :key="m.id" :value="m.id" :label="m.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="overrideForm.startsAt" type="datetime" style="width: 100%" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="overrideForm.endsAt" type="datetime" style="width: 100%" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
        <el-form-item label="事由"><el-input v-model.trim="overrideForm.reason" placeholder="选填" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="overrideDialog = false">取消</el-button>
        <el-button
          type="primary" :loading="saving"
          :disabled="!overrideForm.memberId || !overrideForm.startsAt || !overrideForm.endsAt"
          @click="addOverride"
        >添加</el-button>
      </template>
    </el-dialog>

    <!-- 添加成员弹窗 -->
    <el-dialog v-model="memberDialog" title="添加成员" width="420px">
      <el-form label-width="90px">
        <el-form-item label="账号名" required><el-input v-model.trim="memberForm.name" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model.trim="memberForm.displayName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialog = false">取消</el-button>
        <el-button type="primary" :disabled="!memberForm.name" :loading="saving" @click="addMember">添加</el-button>
      </template>
    </el-dialog>

    <!-- 添加通道弹窗 -->
    <el-dialog v-model="channelDialog" title="添加通知通道" width="480px">
      <el-form label-width="130px">
        <el-form-item label="名称" required><el-input v-model.trim="channelForm.name" /></el-form-item>
        <el-form-item label="平台">
          <el-select v-model="channelForm.platform" style="width: 100%">
            <el-option value="DINGTALK" label="钉钉" />
            <el-option value="WECOM" label="企业微信" />
          </el-select>
        </el-form-item>
        <el-form-item label="webhook 环境变量" required>
          <el-input v-model.trim="channelForm.envKeyWebhook" placeholder="只填环境变量键名，不填 URL 本身" />
        </el-form-item>
        <el-form-item label="加签密钥变量">
          <el-input v-model.trim="channelForm.envKeySecret" placeholder="选填" />
        </el-form-item>
        <el-form-item label="优先级" required>
          <el-input-number v-model="channelForm.priority" :min="1" style="width: 160px" />
        </el-form-item>
        <el-form-item label="fallback 通道">
          <el-switch v-model="channelForm.isFallback" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialog = false">取消</el-button>
        <el-button
          type="primary" :loading="saving"
          :disabled="!channelForm.name || !channelForm.envKeyWebhook || !channelForm.priority"
          @click="addChannel"
        >添加</el-button>
      </template>
    </el-dialog>

    <!-- 通道详情抽屉：含测试通知表单（MANUAL 全新 episode，不参与去重） -->
    <DetailDrawer v-model="channelDrawer" :title="`通道详情：${activeChannel?.name ?? ''}`">
      <template v-if="activeChannel">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="名称">{{ activeChannel.name }}</el-descriptions-item>
          <el-descriptions-item label="平台">{{ platformLabel(activeChannel.platform) }}</el-descriptions-item>
          <el-descriptions-item label="优先级">P{{ activeChannel.priority }}</el-descriptions-item>
          <el-descriptions-item label="fallback">{{ activeChannel.isFallback ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="webhook 环境变量">{{ activeChannel.envKeyWebhook || '—' }}</el-descriptions-item>
          <el-descriptions-item label="加签密钥变量">{{ activeChannel.envKeySecret || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ activeChannel.enabled ? '启用' : '停用' }}</el-descriptions-item>
        </el-descriptions>

        <div class="test-block">
          <div class="blk-title">发送测试通知</div>
          <el-form label-position="top" size="default">
            <el-form-item label="标题" required><el-input v-model.trim="test.title" /></el-form-item>
            <el-form-item label="正文" required><el-input v-model.trim="test.body" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="级别">
              <el-select v-model="test.severity" style="width: 100%">
                <el-option value="" label="无级别" />
                <el-option value="critical" label="critical（P0）" />
                <el-option value="warning" label="warning（P1）" />
                <el-option value="info" label="info（P2）" />
              </el-select>
            </el-form-item>
            <el-button type="primary" :disabled="!test.title || !test.body" :loading="saving" @click="sendTest">发送</el-button>
          </el-form>
          <el-alert
            v-if="testResult" type="success" :closable="false" class="test-result"
            :title="`已派发（${testResult.channel || '无通道——检查通道配置'}），可在「值班通知」或「通知预览」页查看`"
          />
        </div>
      </template>
    </DetailDrawer>
  </div>
</template>

<script setup>
// UI-4 值班管理页（/duty）：默认面=当班横幅 + 未来七天周视图；排班/成员/通道收进二级页签，
// 所有表单走 el-dialog / DetailDrawer。数据全真（DutyQueryController/DutyAdminController）。
// 七天视图按 RotationMath 同规则前端推算（DAILY/WEEKLY + 锚点 + 交接时刻 + 替班窗口），
// 层 0 之外不推算升级链（快照为准）。
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import { fmtTime } from '../utils/format'

const snap = ref(null)
const members = ref([])
const channels = ref([])
const schedule = ref(null)
const layers = ref([])
const overrides = ref([])
const loading = ref(false)
const saving = ref(false)
const tab = ref('schedule')

const schedDialog = ref(false)
const layerDialog = ref(false)
const overrideDialog = ref(false)
const memberDialog = ref(false)
const channelDialog = ref(false)
const channelDrawer = ref(false)
const activeChannel = ref(null)
const testResult = ref(null)

const test = reactive({ title: '', body: '', severity: '' })
const memberForm = reactive({ name: '', displayName: '' })
const channelForm = reactive({ name: '', platform: 'DINGTALK', envKeyWebhook: '', envKeySecret: '', priority: 1, isFallback: false })
const schedForm = reactive({ name: '', timezone: '', rotation: 'WEEKLY', anchorDate: '', handoffTime: '' })
const layerForm = reactive({ memberIds: [] })
const overrideForm = reactive({ memberId: '', startsAt: '', endsAt: '', reason: '' })

const activeMembers = computed(() => members.value.filter(m => m.active))
const stale = computed(() => snap.value && snap.value.validUntil && new Date(snap.value.validUntil) < new Date())

const memberName = id => members.value.find(m => m.id === id)?.name || id
const rotationLabel = r => ({ DAILY: '按天轮换', WEEKLY: '按周轮换' }[r] || r || '—')
const platformLabel = p => ({ DINGTALK: '钉钉', WECOM: '企业微信' }[p] || p)

const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

// 未来七天：规则与后端 RotationMath.position 一致——
// periods = 距锚点交接时刻的整天数（DAILY）或整周数（WEEKLY），对层成员数取模；替班窗口优先。
// 以浏览器本地时区近似排班时区（值班系统部署面同为 Asia/Shanghai），标题已注明"推算"。
const weekDays = computed(() => {
  const sch = schedule.value?.schedule
  if (!schedule.value?.exists || !sch) return []
  const layer0 = layers.value.filter(l => l.memberIds?.length).sort((a, b) => a.layerIndex - b.layerIndex)[0]
  const [hh, mm] = String(sch.handoffTime || '00:00').split(':').map(Number)
  const anchor = new Date(`${sch.anchorDate}T00:00:00`)
  const out = []
  const now = new Date()
  for (let i = 0; i < 7; i++) {
    const day = new Date(now.getFullYear(), now.getMonth(), now.getDate() + i)
    // 该日的判定时刻=当天交接时刻
    const t = new Date(day.getFullYear(), day.getMonth(), day.getDate(), hh || 0, mm || 0)
    let member = ''
    let viaOverride = false
    const ov = overrides.value
      .filter(o => new Date(o.startsAt) <= t && t < new Date(o.endsAt))
      .sort((a, b) => new Date(a.startsAt) - new Date(b.startsAt))[0]
    if (ov) {
      member = memberName(ov.memberId)
      viaOverride = true
    } else if (layer0) {
      const elapsedDays = Math.floor((t - anchor) / 86400000)
      if (elapsedDays >= 0) {
        const periods = sch.rotation === 'WEEKLY' ? Math.floor(elapsedDays / 7) : elapsedDays
        const pos = ((periods % layer0.memberIds.length) + layer0.memberIds.length) % layer0.memberIds.length
        member = memberName(layer0.memberIds[pos])
      }
    }
    out.push({
      key: day.toISOString().slice(0, 10),
      weekLabel: i === 0 ? '今天' : WEEK_LABELS[day.getDay()],
      dateLabel: `${day.getMonth() + 1}月${day.getDate()}日`,
      isToday: i === 0,
      member, viaOverride,
    })
  }
  return out
})

async function reload() {
  loading.value = true
  try {
    const [s, ms, cs, sch] = await Promise.all([
      api('/duty/schedule/snapshot').catch(() => null),
      api('/duty/members'),
      api('/duty/channels'),
      api('/duty/schedule'),
    ])
    snap.value = s
    members.value = ms
    channels.value = cs
    schedule.value = sch
    layers.value = sch.exists ? sch.layers : []
    overrides.value = sch.exists ? sch.overrides : []
    if (sch.exists) {
      const src = sch.schedule
      Object.assign(schedForm, {
        name: schedForm.name || src.name,
        timezone: schedForm.timezone || src.timezone,
        rotation: schedForm.rotation || src.rotation,
        anchorDate: schedForm.anchorDate || src.anchorDate,
        handoffTime: schedForm.handoffTime || src.handoffTime,
      })
    }
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败，请重试')
  } finally {
    loading.value = false
  }
}

// 写操作统一：loading 态 + 成功提示 + 刷新；失败就地 ElMessage
async function run(fn, okText) {
  saving.value = true
  try {
    await fn()
    ElMessage.success(okText)
    await reload()
    return true
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || e.message)
    return false
  } finally {
    saving.value = false
  }
}

function openScheduleDialog() { schedDialog.value = true }

function openChannelDetail(c) {
  activeChannel.value = c
  testResult.value = null
  channelDrawer.value = true
}

const sendTest = () => run(async () => {
  testResult.value = await api('/duty/test-notification', {
    method: 'POST', body: { title: test.title, body: test.body, severity: test.severity || null },
  })
}, '测试通知已派发')

const addMember = () => run(async () => {
  await api('/duty/members', { method: 'POST', body: { ...memberForm } })
  memberForm.name = ''; memberForm.displayName = ''
  memberDialog.value = false
}, '成员已添加')

const toggleMember = m => run(() =>
  api(`/duty/members/${m.id}`, { method: 'PUT', body: { active: !m.active } }),
  m.active ? '成员已停用' : '成员已启用')

const addChannel = () => run(async () => {
  await api('/duty/channels', { method: 'POST', body: { ...channelForm } })
  channelForm.name = ''; channelForm.envKeyWebhook = ''; channelForm.envKeySecret = ''
  channelDialog.value = false
}, '通道已添加')

const toggleChannel = c => run(() =>
  api(`/duty/channels/${c.id}`, { method: 'PUT', body: { enabled: !c.enabled } }),
  c.enabled ? '通道已停用' : '通道已启用')

const saveSchedule = () => run(async () => {
  await api(`/duty/schedule/${schedule.value.schedule.id}`, { method: 'PUT', body: { ...schedForm } })
  schedDialog.value = false
}, '排班参数已保存（排班版本已递增）')

const addLayer = () => run(async () => {
  await api(`/duty/schedule/${schedule.value.schedule.id}/layers`, {
    method: 'POST', body: { memberIds: layerForm.memberIds },
  })
  layerForm.memberIds = []
  layerDialog.value = false
}, '轮值层已追加')

const removeLayer = l => run(() =>
  api(`/duty/layers/${l.id}`, { method: 'DELETE' }), '轮值层已删除')

const toIso = v => (v ? new Date(v).toISOString() : null)

const addOverride = () => run(async () => {
  await api('/duty/overrides', {
    method: 'POST',
    body: {
      scheduleId: schedule.value.schedule.id,
      memberId: overrideForm.memberId,
      startsAt: toIso(overrideForm.startsAt),
      endsAt: toIso(overrideForm.endsAt),
      reason: overrideForm.reason,
    },
  })
  overrideForm.memberId = ''; overrideForm.startsAt = ''; overrideForm.endsAt = ''; overrideForm.reason = ''
  overrideDialog.value = false
}, '替班已添加')

const removeOverride = o => run(() =>
  api(`/duty/overrides/${o.id}`, { method: 'DELETE' }), '替班已删除')

onMounted(reload)
</script>

<style scoped>
.duty-page { display: flex; flex-direction: column; gap: var(--section-gap); }

/* 当班横幅卡 */
.banner { padding: var(--card-pad); }
.banner-main { display: flex; flex-direction: column; gap: 12px; }
.banner-who { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.banner-label { font-size: var(--fs-body); color: var(--ink-2); }
.banner-name { font-size: 22px; font-weight: 700; color: var(--head); }
.banner-meta { display: flex; flex-direction: column; gap: 4px; font-size: var(--fs-body); }
.bm-row { display: flex; gap: 10px; }
.bm-k { flex: none; width: 48px; color: var(--ink-2); font-size: var(--fs-aux); padding-top: 1px; }
.stale-alert { margin-top: 12px; }

/* 未来七天周视图 */
.week-card { padding: var(--card-pad); }
.sec-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); display: flex; align-items: baseline; gap: 10px; }
.sec-hint { font-size: var(--fs-aux); color: var(--ink-2); font-weight: 400; }
.week-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 8px; margin-top: 12px; }
.day-cell {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 12px;
  display: flex; flex-direction: column; gap: 2px; min-width: 0;
}
.day-cell.today { border-color: var(--brand); background: var(--brand-soft); }
.day-head { font-size: var(--fs-aux); color: var(--ink-2); }
.day-cell.today .day-head { color: var(--brand); font-weight: 600; }
.day-date { font-size: var(--fs-aux); color: var(--ink-2); }
.day-who { margin-top: 4px; font-size: var(--fs-body); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.day-who b { color: var(--head); }
.day-none { color: var(--ink-2); }
.week-empty { margin-top: 12px; display: flex; align-items: center; gap: 12px; color: var(--ink-2); font-size: var(--fs-body); flex-wrap: wrap; }

/* 二级页签 */
.admin-card { padding: 8px var(--card-pad) var(--card-pad); }
.sched-block { margin-bottom: 20px; }
.blk-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 12px 0 8px; }
.blk-title { font-size: var(--fs-body); font-weight: 600; color: var(--head); }
.blk-hint { margin-left: 8px; font-size: var(--fs-aux); color: var(--ink-2); font-weight: 400; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.create-guide { text-align: center; font-size: var(--fs-body); color: var(--ink-2); }
.create-guide p { margin: 2px 0; }
.create-guide .el-button { margin-top: 10px; }

/* 通道详情抽屉内测试通知 */
.test-block { margin-top: 20px; border-top: 1px solid var(--line); padding-top: 16px; }
.test-result { margin-top: 12px; }

@media (max-width: 1100px) {
  .week-grid { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 720px) {
  .week-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>

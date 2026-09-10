<template>
  <div class="create-page">
    <PageHeader
      title="新建演练"
      subtitle="三步发起一次完整演练作业；启动命令依赖 DR-02 持久化作业，本批仅搭建页面结构。"
    >
      <template #actions>
        <el-button @click="router.push('/drills')">返回列表</el-button>
      </template>
    </PageHeader>

    <div class="card steps-card">
      <el-steps :active="step" align-center finish-status="success">
        <el-step title="选择场景" description="已发布场景模板" />
        <el-step title="配置受限参数" description="时长 / 流量 / 靶场" />
        <el-step title="检查并发起" description="服务端预检后提交" />
      </el-steps>
    </div>

    <!-- 第一步：选择场景。场景目录接口依赖 DR-02，以下为 eval-scenarios.yml 静态说明，不可选择 -->
    <div v-show="step === 0" class="card step-body">
      <div class="step-title">选择场景</div>
      <el-alert type="info" :closable="false" show-icon class="step-alert"
        title="场景目录接口（GET /api/drills/templates）依赖 DR-02，暂未开放"
      >
        以下为 deploy/alert/eval/eval-scenarios.yml（registry v2）的静态说明文案，仅供了解场景内容，
        <b>当前不可选择</b>。首期将开放 S1~S5 中已通过数据源/恢复验证的场景，其余将标注「不可用」及原因。
      </el-alert>
      <div class="scenario-grid">
        <div v-for="s in staticScenarios" :key="s.id" class="scenario-card disabled" :title="'场景目录接口未就绪（依赖 DR-02），暂不可选择'">
          <div class="sc-head">
            <span class="sc-id mono">{{ s.id }}</span>
            <span class="sc-name">{{ s.name }}</span>
          </div>
          <div class="sc-row"><span class="sc-k">类型</span>{{ s.type }}</div>
          <div class="sc-row"><span class="sc-k">故障源</span>{{ s.source }}</div>
          <div class="sc-row"><span class="sc-k">症状</span>{{ s.symptom }}</div>
          <div class="sc-state">不可用：场景目录接口依赖 DR-02</div>
        </div>
      </div>
    </div>

    <!-- 第二步：配置受限参数。目标从后端白名单选择，不允许输入服务器 IP / 任意 SQL / shell -->
    <div v-show="step === 1" class="card step-body">
      <div class="step-title">配置受限参数</div>
      <el-alert type="info" :closable="false" show-icon class="step-alert"
        title="参数提交与校验依赖 DR-02 服务端预检，本步控件暂未开放"
      >
        参数面仅开放持续时间、测试流量规模、靶场与可选关联评测版本；实际 TTL、预热与恢复窗口由后端按白名单计算，
        前端不各自推算。目标资源从后端白名单选择，不输入服务器 IP、任意 SQL 或 shell 命令。
      </el-alert>
      <el-form label-width="140px" class="param-form">
        <el-form-item label="持续时间">
          <el-input placeholder="依赖 DR-02，暂未开放" disabled />
        </el-form-item>
        <el-form-item label="测试流量规模">
          <el-input placeholder="依赖 DR-02，暂未开放" disabled />
        </el-form-item>
        <el-form-item label="靶场">
          <el-select placeholder="目标白名单由后端下发（DR-02）" disabled />
        </el-form-item>
        <el-form-item label="关联评测版本">
          <el-select placeholder="可选；关联一次评测实验，非必填" disabled />
        </el-form-item>
      </el-form>
    </div>

    <!-- 第三步：检查并发起。预检必须服务端重新执行，旧预览不保证现在仍可启动 -->
    <div v-show="step === 2" class="card step-body">
      <div class="step-title">检查并发起</div>
      <el-alert type="warning" :closable="false" show-icon class="step-alert"
        title="预检须由服务端在启动前重新执行（POST /api/drills/preview，依赖 DR-02）"
      >
        下方为预览骨架，实际值由服务端预检返回：靶场健康、资源水位、旧故障残留、管理面/观测面、
        恢复能力与资源占用情况。旧预览不能保证当前仍可启动。
      </el-alert>
      <dl class="preview-list">
        <div class="pv-row"><dt>目标资源</dt><dd>—（服务端预检返回，依赖 DR-02）</dd></div>
        <div class="pv-row"><dt>预计等待时间</dt><dd>—（后端按 timing 计算真实阶段与倒计时，不伪造精确百分比）</dd></div>
        <div class="pv-row"><dt>停止条件</dt><dd>—（触及停止阈值时停止测试流量并发起恢复）</dd></div>
        <div class="pv-row"><dt>恢复方式</dt><dd>—（一旦注入可能发生，停止或失败都必须先进入恢复路径，§7.4）</dd></div>
        <div class="pv-row"><dt>资源占用</dt><dd>—（同一靶场同一时间仅允许一个活动演练，数据库原子占位）</dd></div>
      </dl>
      <div class="submit-row">
        <el-button type="primary" disabled title="启动命令依赖 DR-02 持久化作业，暂未开放">开始演练</el-button>
        <span class="submit-note">启动命令依赖 DR-02 持久化作业，暂未开放；届时将一次提交完整作业并返回 202 受理。</span>
      </div>
    </div>

    <div class="step-nav">
      <el-button :disabled="step === 0" @click="step -= 1">上一步</el-button>
      <el-button v-if="step < 2" type="primary" plain @click="step += 1">下一步</el-button>
      <span class="step-nav-note">各步可回看；当前所有提交动作均未开放（依赖 DR-02）。</span>
    </div>
  </div>
</template>

<script setup>
// 新建演练（/drills/new，DR-01）：三步结构照 §7.2 搭好——
// ① 选择场景：场景目录接口依赖 DR-02，仅展示 eval-scenarios.yml 静态说明（标注不可选）；
// ② 配置受限参数：控件禁用；③ 检查并发起：预览骨架 + 「开始演练」禁用并注明原因。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'

const router = useRouter()
const step = ref(0)

// eval-scenarios.yml（registry_version: 2）S1~S5 的静态说明——仅展示，不构成可选目录
const staticScenarios = [
  { id: 'S1', name: 'paymentFailure=50%', type: '业务链路错误率', source: 'AM0 flagd', symptom: 'checkout 烧损率告警（page/ticket）' },
  { id: 'S2', name: 'flagd paymentUnreachable', type: '依赖不可达', source: 'AM0 flagd', symptom: 'checkout 烧损率告警（page/ticket）' },
  { id: 'S3', name: 'F1 幂等失效', type: '业务完整性', source: 'AM2 靶场（Arena）', symptom: 'ArenaDuplicateOrders（page）' },
  { id: 'S4', name: 'F2 状态回跳', type: '状态机非法迁移', source: 'AM2 靶场（Arena）', symptom: 'ArenaIllegalTransitions（page）' },
  { id: 'S5', name: 'F3 超时结果未知', type: '中间态悬挂', source: 'AM2 靶场（Arena）', symptom: 'ArenaOrderStuck（page）' },
]
</script>

<style scoped>
.create-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.steps-card { padding: var(--card-pad); }
.step-body { padding: var(--card-pad); }
.step-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-bottom: 12px; }
.step-alert { margin-bottom: 16px; }

.scenario-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.scenario-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px;
  background: var(--card);
}
.scenario-card.disabled { opacity: .72; cursor: not-allowed; }
.sc-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.sc-id {
  font-size: var(--fs-aux); font-weight: 700; color: var(--brand);
  background: var(--brand-soft); border-radius: var(--radius-ctl); padding: 1px 6px;
}
.sc-name { font-size: var(--fs-body); font-weight: 600; color: var(--head); }
.sc-row { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.7; }
.sc-k { display: inline-block; width: 44px; color: var(--ink); font-weight: 600; }
.sc-state {
  margin-top: 8px; font-size: var(--fs-aux); color: var(--warn);
  background: var(--warn-bg); border-radius: var(--radius-ctl); padding: 3px 8px;
}
.mono { font-family: var(--mono, monospace); }

.param-form { max-width: 560px; }

.preview-list { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.pv-row { display: flex; gap: 16px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.pv-row:last-child { border-bottom: none; }
.pv-row dt { flex: none; width: 120px; font-size: var(--fs-body); font-weight: 600; color: var(--ink); }
.pv-row dd { font-size: var(--fs-body); color: var(--ink-2); }

.submit-row { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.submit-note { font-size: var(--fs-aux); color: var(--ink-2); }

.step-nav { display: flex; align-items: center; gap: 12px; }
.step-nav-note { font-size: var(--fs-aux); color: var(--ink-2); }
</style>

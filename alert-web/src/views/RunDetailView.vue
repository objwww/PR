<template>
  <div class="run-page" v-if="run">
    <div class="crumb">
      <span>
        <b>首页</b><span class="sep">/</span>调查<span class="sep">/</span>
        <router-link to="/runs">调查队列</router-link><span class="sep">/</span>
        <b>调查 {{ shortId(run.id) }}</b>
      </span>
      <span class="crumb-right">数据更新至 {{ fmtTime(loadedAt) }}</span>
    </div>

    <!-- 顶部摘要：告警 / 状态 chip / 耗时 / 已用费用（rca_model_call 实账，未定价不冒充 ¥0）+ 次级操作 -->
    <div class="card runhead">
      <router-link v-if="run.incident" class="inc-link" :to="`/alerts/${run.incident}`" :title="run.incident">
        告警 {{ shortId(run.incident) }}
      </router-link>
      <StatusBadge :status="run.status" />
      <span class="mini">耗时 {{ listRow?.duration ?? '—' }}</span>
      <span class="mini">预算 token：{{ run.budget?.token?.used ?? '—' }}</span>
      <span class="mini" :title="costTitle">费用：{{ costText }}</span>
      <span class="mini">任务 {{ run.progress.done }}/{{ run.progress.total }}</span>
      <span v-if="listRow?.blocker" class="mini blocker" :title="listRow.blocker">卡点：{{ listRow.blocker }}</span>
      <span class="ops">
        <template v-if="runActive">
          <el-button size="small" :disabled="cmdPending" @click="submitHint">补充线索</el-button>
          <el-button size="small" :disabled="cmdPending" @click="submitFeedback">报告反馈</el-button>
          <el-button size="small" type="danger" plain :disabled="cmdPending" @click="submitCancel">取消调查</el-button>
        </template>
      </span>
    </div>

    <div class="card tabs">
      <button
        v-for="t in viewTabs"
        :key="t.key"
        class="tab"
        :class="{ cur: viewTab === t.key }"
        @click="switchTab(t.key)"
      >{{ t.label }}</button>
    </div>

    <!-- ============ 摘要（默认）：当前结论与待补证 ============ -->
    <template v-if="viewTab === 'summary'">
      <div class="card panel">
        <div class="lbl">当前结论</div>
        <template v-if="currentConclusion">
          <div class="conclusion">
            <b>{{ currentConclusion.text }}</b>
            <span class="mini">（{{ currentConclusion.code }} ｜ {{ currentConclusion.verdict }}）</span>
          </div>
        </template>
        <template v-else>
          <div class="conclusion-pending">
            <el-tag type="warning" effect="dark" disable-transitions>原因待确认</el-tag>
            <span v-if="runActive" class="mini"><el-icon class="is-loading"><Loading /></el-icon> 调查进行中，等待事件与结论</span>
            <span v-else class="mini">本次调查未产生有效结论</span>
          </div>
        </template>
      </div>

      <div class="card panel">
        <div class="lbl">待补证</div>
        <template v-if="pendingHypotheses.length">
          <div v-for="c in pendingHypotheses" :key="c.id" class="line-item">
            {{ c.text }} <span class="mini">（{{ c.code }} ｜ {{ c.verdict }}）</span>
          </div>
        </template>
        <div v-else class="muted">暂无待补证项</div>
      </div>

      <div class="card panel">
        <div class="lbl">调查进展</div>
        <div class="progress-line">
          共 {{ run.progress.total }} 个任务：完成 {{ run.progress.done }} ｜ 运行 {{ run.progress.running }} ｜ 阻塞 {{ run.progress.blocked }}
          <template v-if="run.engine">｜ 引擎 {{ run.engine }}</template>
        </div>
        <div v-if="listRow?.blocker" class="blocker-box">卡点：{{ listRow.blocker }}</div>
      </div>

      <div class="card panel">
        <div class="lbl">最近事件</div>
        <template v-if="recentEvents.length">
          <div v-for="e in recentEvents" :key="e.seq" class="line-item">
            <span class="seq">seq{{ e.seq }}</span>
            <b>{{ eventZh(e.type) }}</b>
            <span class="mini">{{ e.summary }}</span>
          </div>
          <el-button text type="primary" @click="switchTab('events')">查看全部事件 →</el-button>
        </template>
        <div v-else class="muted">
          <template v-if="runActive"><el-icon class="is-loading"><Loading /></el-icon> 等待事件</template>
          <template v-else>暂无事件</template>
        </div>
      </div>
    </template>

    <!-- ============ 执行过程：Vue Flow DAG，画布为主 ============ -->
    <template v-else-if="viewTab === 'dag'">
      <div class="card toolbar">
        <el-button size="small" @click="dagRef?.fit()">适应画布</el-button>
        <el-button size="small" :type="abnormalOnly ? 'primary' : 'default'" @click="abnormalOnly = !abnormalOnly">仅看异常</el-button>
        <el-button size="small" :type="neighborFocus ? 'primary' : 'default'" :disabled="!selectedTask" @click="neighborFocus = !neighborFocus">上游/下游</el-button>
        <el-popover placement="bottom-start" trigger="click" width="520">
          <template #reference>
            <el-button size="small" text>状态图例</el-button>
          </template>
          <table class="legend">
            <thead>
              <tr><th>状态（节点内原文）</th><th>图形约定</th><th>含义</th></tr>
            </thead>
            <tbody>
              <tr v-for="code in statusOrder" :key="code">
                <td><b>{{ code }}</b> {{ statusStyle[code].zh }}</td>
                <td>{{ statusStyle[code].graphic }}</td>
                <td>{{ statusStyle[code].desc }}</td>
              </tr>
            </tbody>
          </table>
        </el-popover>
        <span class="flex-spacer" />
        <span class="tinfo">任务 {{ run.progress.total }}（完成 {{ run.progress.done }} / 运行 {{ run.progress.running }} / 阻塞 {{ run.progress.blocked }}）</span>
      </div>

      <div class="card dag-card">
        <RunDag
          ref="dagRef"
          :tasks="dagTasks"
          :edges="edges"
          :selected-id="selectedTaskId"
          :abnormal-only="abnormalOnly"
          :neighbor-focus="neighborFocus"
          @select="onSelectTask"
        />
        <div class="note">点击节点查看任务详情；实时事件驱动节点变色</div>
      </div>

      <!-- 选中节点才开任务详情抽屉（360~420px） -->
      <DetailDrawer
        :model-value="!!selectedTask"
        :title="`任务详情：${selectedTask?.name ?? ''}`"
        :size="400"
        @update:model-value="v => { if (!v) selectedTaskId = null }"
      >
        <template v-if="selectedTask">
          <div class="box">
            <StatusBadge :status="selectedTask.status" />
            <span class="mini">{{ statusStyle[selectedTask.status]?.zh }}</span><br>
            任务 {{ selectedTask.name }} ｜ 优先级 {{ selectedTask.priority }}
            ｜ 轮次 round {{ selectedTask.roundId ?? 0 }}<br>
            角色：<template v-if="selectedTask.roleId">{{ selectedTask.roleId }}@{{ selectedTask.roleVersion }}</template>
            <span v-else class="muted">主 Agent / 未绑定</span><br>
            截止时间 {{ fmtTime(selectedTask.deadline) }}
            <template v-if="selectedTask.lease"><br>租约：{{ selectedTask.lease.worker }}（epoch {{ selectedTask.lease.epoch }}）</template>
            <br>尝试次数：{{ selectedTask.attempts }}
          </div>
          <div class="box">
            <b>依赖</b>：
            <template v-if="selectedTask.deps.length">
              <span v-for="(d, i) in selectedTask.deps" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }}（{{ statusStyle[d.status]?.zh ?? d.status }}）
              </span>
            </template>
            <template v-else>无上游</template>
            <br>
            <b>下游</b>：
            <template v-if="selectedTask.downstream.length">
              <span v-for="(d, i) in selectedTask.downstream" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }}（{{ statusStyle[d.status]?.zh ?? d.status }}）
              </span>
            </template>
            <template v-else>无下游</template>
          </div>
          <div class="drawer-ops">
            <el-button size="small" @click="copyTaskLink">复制任务链接</el-button>
            <el-button size="small" type="primary" @click="viewTaskEvents">仅看此任务事件</el-button>
          </div>
        </template>
      </DetailDrawer>
    </template>

    <!-- ============ 事件流：Transcript 卡片流 ============ -->
    <template v-else-if="viewTab === 'events'">
      <div class="card panel">
        <div class="ev-toolbar">
          范围：
          <el-button size="small" :type="eventScope === 'all' ? 'primary' : 'default'" @click="eventScope = 'all'">全部事件</el-button>
          <el-button
            size="small" :type="eventScope === 'task' ? 'primary' : 'default'"
            :disabled="!selectedTask" @click="eventScope = 'task'"
          >当前任务：{{ selectedTask?.name ?? '—' }}</el-button>
          <el-button size="small" :type="errorsOnly ? 'primary' : 'default'" @click="errorsOnly = !errorsOnly">仅错误</el-button>
          <el-select v-model="typeFilter" size="small" class="w-type" placeholder="全部类型" clearable>
            <el-option v-for="t in eventTypes" :key="t" :value="t" :label="eventZh(t)" />
          </el-select>
          <el-input v-model="search" size="small" class="w-search" placeholder="搜索事件码 / seq" clearable />
          <span class="flex-spacer" />
          <el-button size="small" @click="downloadEvents">下载白名单事件</el-button>
        </div>

        <div class="ev-scroll-wrap">
          <transition name="fade">
            <div v-if="newCount > 0" class="new-bar" @click="scrollEvToBottom">有 {{ newCount }} 条新事件，点击查看</div>
          </transition>

          <div ref="evListRef" class="ev-list" @scroll.passive="onEvScroll">
            <div
              v-for="ev in filteredEvents"
              :key="ev.seq"
              class="ev-card"
              :class="['lv-' + (ev.level ?? 'info'), { open: expandedSeq === ev.seq }]"
              @click="expandedSeq = expandedSeq === ev.seq ? null : ev.seq"
            >
              <div class="ev-line">
                <i class="lv-dot" />
                <b class="ev-name">{{ eventZh(ev.type) }}</b>
                <code>{{ ev.type }}</code>
                <span class="ev-summary">{{ ev.summary }}</span>
                <el-tag v-if="ev.taskName ?? taskNameOf(ev)" size="small" type="info" disable-transitions>
                  {{ ev.taskName ?? taskNameOf(ev) }}
                </el-tag>
                <span class="ev-meta">seq{{ ev.seq }} ｜ {{ fmtTime(ev.createdAt) }}</span>
              </div>
              <div v-if="expandedSeq === ev.seq" class="ev-detail">
                <pre v-if="ev.payload && Object.keys(ev.payload).length">{{ JSON.stringify(ev.payload, null, 2) }}</pre>
                <div class="mini">
                  seq={{ ev.seq }} ｜ task_id={{ ev.taskId ?? '—' }} ｜ level={{ ev.level }}<br>
                  脱敏红线：思考过程 / 原始提示词 / 密钥 / 完整工具参数不进前端，关联对象仅白名单引用与摘要（digest）
                </div>
              </div>
            </div>
            <div v-if="filteredEvents.length === 0" class="ev-empty">
              <template v-if="runActive && !allEvents.length">
                <el-icon class="is-loading"><Loading /></el-icon> 调查进行中，等待事件
              </template>
              <template v-else>当前筛选无事件</template>
            </div>
          </div>
        </div>

        <div class="ev-status">
          {{ sseStatusText }}（游标 seq={{ lastSeq }}）｜ 已加载 {{ allEvents.length }} 条 ｜ 断线自动重连续传；检测到 seq 缺口时全量重同步
        </div>
      </div>
    </template>

    <!-- ============ Claim 与证据：证据 / 假设 / 结论三层分级 ============ -->
    <template v-else-if="viewTab === 'claims'">
      <div v-for="g in claimGroups" :key="g.key" class="card panel">
        <div class="lbl">{{ g.title }}</div>
        <template v-if="g.items.length">
          <div v-for="c in g.items" :key="c.id" class="box">
            <b>{{ c.text }}</b> <span class="mini">（{{ c.code }}）</span>
            ｜ {{ c.verdict }} ｜ {{ c.current ? '当前有效' : '已被取代' }}
            <el-button size="small" text type="primary" @click="toggleClaim(c.id)">
              证据 {{ c.evidences?.length ?? 0 }} 条 {{ openClaims.has(c.id) ? '▲' : '▼' }}
            </el-button>
            <div v-if="openClaims.has(c.id)" class="ev-refs">
              <div v-for="e in c.evidences" :key="e.id" class="line-item">
                {{ e.id }} {{ e.desc }} <code>{{ e.digest }}</code><template v-if="e.window"> [{{ e.window }}]</template>
              </div>
              <div class="mini">来源 / 时间窗 / 采集状态可展开（校验 observed_generation / schema_version / payload_digest）</div>
            </div>
          </div>
        </template>
        <div v-else class="muted">{{ g.emptyText }}</div>
      </div>
    </template>

    <!-- ============ 报告（GET /api/rca-runs/{id}/report 真读面；raw_text 后端不透出）============ -->
    <template v-else-if="viewTab === 'report'">
      <div class="card panel">
        <div class="lbl">报告与发布状态</div>
        <EmptyState v-if="reportLoadState === 'error'" kind="error" :description="reportError" @retry="loadReport" />
        <div v-else-if="reportLoadState !== 'ok'" class="muted">
          <el-icon class="is-loading"><Loading /></el-icon> 报告加载中…
        </div>
        <!-- NONE：run 无报告行——区分进行中与已结束，不冒充占位文案 -->
        <div v-else-if="report?.state === 'NONE'" class="muted">
          <template v-if="runActive">
            <el-icon class="is-loading"><Loading /></el-icon> 调查进行中，尚未产生报告
          </template>
          <template v-else>本次调查未产生报告</template>
        </div>
        <template v-else-if="report">
          <KvTable :data="reportHeadKv" />
          <div v-if="report.supersededCount > 0" class="mini">
            本调查共产生 {{ report.supersededCount + 1 }} 份报告（重试留痕），此处展示最新一份，早前 {{ report.supersededCount }} 份已被取代
          </div>

          <!-- REJECTED：结构验证拒绝链 + 原文折叠可查（审计面；未过验证的包不作结论渲染） -->
          <template v-if="report.state === 'REJECTED'">
            <el-alert type="error" :closable="false" show-icon class="rpt-alert"
              :title="`报告未通过结构验证（${report.validationStatus}），未进入发布链`" />
            <div class="box">
              <b>拒绝原因</b>
              <template v-if="(report.validationErrors ?? []).length">
                <div v-for="(err, i) in report.validationErrors" :key="i" class="line-item">· {{ err }}</div>
              </template>
              <div v-else class="muted">无逐条原因记录</div>
            </div>
            <el-button size="small" text type="primary" @click="reportPkgOpen = !reportPkgOpen">
              报告原文（未通过验证，仅供审计）{{ reportPkgOpen ? '▲' : '▼' }}
            </el-button>
            <pre v-if="reportPkgOpen" class="pkg-raw">{{ reportPkgRaw || '（空）' }}</pre>
          </template>

          <!-- OK：六段式结构化渲染（键名 = EvidencePackageValidator 六段式 schema：summary/root_cause/evidence/impact/remediation/references，v2 另带 claims） -->
          <template v-else>
            <template v-if="reportSections.length">
              <div v-for="sec in reportSections" :key="sec.key" class="box">
                <div class="lbl">{{ sec.title }}</div>
                <div v-if="sec.kind === 'text'" class="rpt-text">{{ sec.value }}</div>
                <template v-else-if="sec.kind === 'list'">
                  <template v-if="sec.items.length">
                    <div v-for="(item, i) in sec.items" :key="i" class="line-item">
                      <template v-if="typeof item === 'string'">· {{ item }}</template>
                      <pre v-else class="pkg-raw">{{ JSON.stringify(item, null, 2) }}</pre>
                    </div>
                  </template>
                  <div v-else class="muted">无</div>
                </template>
                <pre v-else class="pkg-raw">{{ JSON.stringify(sec.value, null, 2) }}</pre>
              </div>
            </template>
            <!-- 包可解析但六段皆缺 → 如实展示原文，不伪造结构 -->
            <pre v-else class="pkg-raw">{{ reportPkgRaw || '（报告包为空）' }}</pre>
          </template>

          <!-- 发布状态：无记录 null 如实；SENT/DEAD/RETRY_WAIT 分态展示 -->
          <div class="lbl pub-lbl">发布状态</div>
          <template v-if="report.publication">
            <div class="pub-line">
              <el-tag :type="pubTagType" effect="plain" size="small" disable-transitions>{{ pubStateText }}</el-tag>
              <span class="mini">尝试 {{ report.publication.attemptCount }}/{{ report.publication.maxAttempts }}</span>
              <span class="mini">更新于 {{ fmtTime(report.publication.updatedAt) }}</span>
            </div>
            <div v-if="pubLastError" class="blocker-box">最近错误：{{ pubLastError }}</div>
          </template>
          <div v-else class="muted">无发布记录——报告未进入外发链</div>
        </template>
      </div>
    </template>

    <!-- ============ 运行详情：预算 / lease / attempt / config digest 等技术字段 ============ -->
    <template v-else-if="viewTab === 'meta'">
      <div class="card panel">
        <div class="lbl">基本信息</div>
        <KvTable :data="runHeadKv" />
      </div>
      <div class="card panel">
        <div class="lbl">预算分项</div>
        <KvTable v-if="run.budget" :data="run.budget" />
        <div v-else class="muted">预算账本无读面，投影未提供（不回填示意值）</div>
      </div>
      <!-- §三.5：费用数据源 = rca_model_call（RV08 红线，非 PR 域账本） -->
      <div class="card panel">
        <div class="lbl">模型调用与费用</div>
        <template v-if="usage">
          <KvTable :data="usageKv" />
          <div v-if="usage.usageMissing > 0" class="blocker-box">
            {{ usage.usageMissing }} 笔调用用量未回报，费用为下限
          </div>
        </template>
        <div v-else class="muted">无模型调用账本记录</div>
      </div>
      <!-- §三.5：角色/轮次/依赖透出；旧 run 无 V46 绑定 → 如实降级 -->
      <div class="card panel">
        <div class="lbl">角色与轮次</div>
        <template v-if="hasRoleData">
          <el-table :data="roleRows" size="small">
            <el-table-column label="任务" min-width="150">
              <template #default="{ row }"><b>{{ row.name }}</b></template>
            </el-table-column>
            <el-table-column label="轮次" width="90" align="center">
              <template #default="{ row }">round {{ row.roundId ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="角色" min-width="170">
              <template #default="{ row }">
                <template v-if="row.roleId">{{ row.roleId }}@{{ row.roleVersion }}</template>
                <span v-else class="muted">主 Agent / 未绑定</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }"><StatusBadge :status="row.status" /></template>
            </el-table-column>
            <el-table-column prop="attempts" label="尝试" width="70" align="right" />
            <el-table-column label="编排" min-width="150">
              <template #default="{ row }">{{ row.orchestration }}</template>
            </el-table-column>
            <el-table-column label="父请求" min-width="100">
              <template #default="{ row }">
                <code v-if="row.parentRequestId">{{ shortId(row.parentRequestId) }}</code>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="note">
            串行/并发为依赖投影（edges）的读面推断：同一上游任务在同一轮次派出多个下游任务 → 并发分支；链式单下游 → 串行
          </div>
        </template>
        <div v-else class="muted">此调查为旧版单角色执行，无角色绑定数据</div>
      </div>
      <div class="card panel">
        <div class="lbl">任务与尝试（rca_task 投影）</div>
        <el-table :data="dagTasks" size="small">
          <el-table-column label="任务" min-width="160">
            <template #default="{ row }"><b>{{ row.name }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="80" align="right" />
          <el-table-column label="截止时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.deadline) }}</template>
          </el-table-column>
          <el-table-column label="租约" min-width="150">
            <template #default="{ row }">
              <template v-if="row.lease">{{ row.lease.worker }} · epoch {{ row.lease.epoch }}</template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="attempts" label="尝试次数" width="110" align="right" />
          <el-table-column label="任务 ID" min-width="110">
            <template #default="{ row }"><code>{{ shortId(row.taskId) }}</code></template>
          </el-table-column>
        </el-table>
      </div>

      <!-- ============ EV-10 配置切换（方案 §4.4 高级操作「在安全点更新此调查」）============ -->
      <div class="card panel">
        <div class="lbl">配置切换</div>
        <el-alert
          v-if="cfgState === 'not-ready'" type="warning" :closable="false" show-icon
          title="配置代际查询接口不可用"
          :description="cfgNotReadyText"
        />
        <template v-else-if="cfgState === 'error'">
          <div class="muted">配置代际历史加载失败——本页不猜测切换状态。</div>
          <el-button size="small" :loading="cfgLoading" @click="loadCfgEpochs">重试</el-button>
        </template>
        <template v-else-if="cfgState === 'ok'">
          <div class="cfg-line">
            <el-tag :type="cfgMixed ? 'warning' : 'success'" effect="plain" size="small">
              {{ cfgMixed ? '混合配置（MIXED_CONFIG）' : '单一配置' }}
            </el-tag>
            <span v-if="cfgMixed" class="mini">此调查跨多个配置版本，不作为单版本质量样本（改造方案 §4.4）</span>
            <span class="flex-spacer" />
            <el-button size="small" :loading="cfgLoading" @click="loadCfgEpochs">刷新</el-button>
            <el-button
              size="small" type="primary" plain
              :disabled="!canCfgSwitch" :title="cfgSwitchHint"
              @click="openSwitchDialog"
            >在安全点更新此调查</el-button>
          </div>
          <div v-if="cfgSwitchHint" class="mini cfg-hint">{{ cfgSwitchHint }}</div>
          <el-table :data="cfgEpochs.epochs ?? []" size="small" v-loading="cfgLoading">
            <el-table-column prop="config_epoch" label="epoch" width="80" align="center" />
            <el-table-column label="release digest" min-width="200">
              <template #default="{ row }"><code>{{ row.release_digest }}</code></template>
            </el-table-column>
            <el-table-column label="来源命令" min-width="110">
              <template #default="{ row }">
                <code v-if="row.source_command_id">{{ shortId(row.source_command_id) }}</code>
                <span v-else class="muted">准入播种</span>
              </template>
            </el-table-column>
            <el-table-column prop="applied_by" label="操作者" width="120">
              <template #default="{ row }">{{ row.applied_by ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip />
            <el-table-column label="时间" width="165">
              <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
            </el-table-column>
            <template #empty>
              <span class="muted">无配置代际史——此调查铸造于 EN-04 之前，服务端不支持热切（fail-closed）</span>
            </template>
          </el-table>
          <!-- 最近一次热切命令的真实状态：受理≠生效，生效以 epoch 历史出现新行（source_command_id 匹配）为准 -->
          <div v-if="switchCmd" class="cfg-cmd">
            <el-tag
              :type="{ WAITING_SAFE_POINT: 'warning', APPLIED: 'success', REJECTED: 'danger', EXPIRED: 'info' }[switchCmd.state] ?? 'info'"
              effect="plain" size="small"
            >{{ switchCmdStateText }}</el-tag>
            <span class="mini">命令 {{ shortId(switchCmd.commandId) }} → epoch {{ switchCmd.targetEpoch }}（{{ shortId(switchCmd.targetDigest) }}…）</span>
            <span v-if="switchCmd.reason" class="mini">原因：{{ switchCmd.reason }}</span>
          </div>
        </template>
        <div v-else class="muted" v-loading="cfgLoading">配置代际历史加载中…</div>
      </div>
    </template>
  </div>

  <div v-else-if="loadError" class="card"><EmptyState kind="error" :description="loadError" @retry="loadDetail" /></div>
  <div v-else class="loading card" v-loading="true" />

  <!-- EV-10 热切对话框：目标 bundle 复用 /api/release-assets/bundles；兼容性由服务端
       核验（H13：DAG 形状/角色/输出 schema/工具权限不扩），前端只展示不预判 -->
  <el-dialog v-model="switchVisible" title="在安全点更新此调查" width="640px">
    <div v-loading="switchBundlesLoading">
      <template v-if="switchBundlesError">
        <el-alert type="error" :closable="false" show-icon title="版本清单加载失败" :description="switchBundlesError" />
      </template>
      <template v-else>
        <div class="mini dlg-note">
          目标版本将在<b>安全点</b>（完整 round 结束 + 无在飞模型动作 + driver 持租）才生效；
          服务端会核验兼容性，不兼容（DAG 漂移/扩工具权限/输出 schema 变更）将被拒绝并建议关联新调查。
          命令等待期限 15 分钟，超时未达安全点由后端翻牌过期。
        </div>
        <el-select
          v-model="switchTarget" class="dlg-select" placeholder="选择目标配置包版本"
          filterable :disabled="switchPending"
        >
          <el-option
            v-for="b in switchBundles" :key="b.digest"
            :value="b.digest"
            :label="`revision ${b.revision} · ${b.digest.slice(0, 16)}…${b.active ? '（当前激活）' : ''}${b.digest === cfgCurrentDigest ? '（此调查当前版本）' : ''}`"
            :disabled="b.digest === cfgCurrentDigest"
          />
        </el-select>
        <el-input
          v-model="switchReason" type="textarea" :rows="2" maxlength="512" show-word-limit
          placeholder="切换原因（必填，≤512 字，随命令与 epoch 历史留痕）" :disabled="switchPending"
        />
      </template>
    </div>
    <template #footer>
      <el-button :disabled="switchPending" @click="switchVisible = false">取消</el-button>
      <el-button
        type="primary" :loading="switchPending"
        :disabled="!switchTarget || !switchReason.trim() || !!switchBundlesError"
        @click="submitSwitch"
      >提交切换命令</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
// UI-3 调查详情（/runs/:runId）：摘要默认 + 执行过程(DAG) + 事件流(Transcript) +
// Claim 与证据(三层) + 报告 + 运行详情。
//
// ===== 事件流真接入（EX-C1 起真流，保留接线）=====
//  1) POST /api/rca-runs/{id}/events/stream-ticket 换 stream ticket（TTL 30s、单次、绑 run+主体）
//  2) new EventSource(.../stream?ticket=...)；初次读取用 REST ?after_seq= 游标做全量种子
//  3) SSE 每条消息写 id: seq，断线重连自动带 Last-Event-ID（服务端归一为 after_seq 游标）
//  4) resync 命名事件 → 停止增量，REST 全量重同步后重开流
//  5) FUT-33：断线不得改变 Run 状态；票单次有效——ES onerror 统一关流重新换票
//
// ===== 命令真接线（POST /api/rca-runs/{id}/commands）=====
//  命令体 {type: CANCEL|HINT|FEEDBACK|CONFIG_SWITCH, idempotencyKey, expectedRevision, payload}；
//  expectedRevision 锚 rca_run.last_event_seq——详情投影不暴露该字段，但事件端点
//  返回 latestSeq（同一计数器），以此作为修订号；SSE 每事件推进 revision。
//  409=修订过期（零副作用）→ 提示并刷新；403=终态越权。按钮仅在 Run 活动态显示。
//  CONFIG_SWITCH（EV-10）：payload 白名单四键（expected_config_epoch/target_release_digest/
//  reason/deadline），202=WAITING_SAFE_POINT 仅受理，生效以 config-epochs 读面为准。
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { ApiNotReadyError, getRunConfigEpochs, listBundles } from '../api/versions'
import RunDag from '../components/RunDag.vue'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'
import KvTable from '../components/common/KvTable.vue'
import { STATUS_STYLE, STATUS_ORDER } from '../components/RunDagStatus.js'
import { EVENT_TYPE_ZH, zh } from '../dict/displayNameZh.js'
import { useSseStore } from '../stores/sseStatus.js'
import { fmtTime } from '../utils/format'

const sse = useSseStore()
const route = useRoute()

const detail = ref(null)
const listRow = ref(null) // 队列投影行（耗时/卡点；详情投影无 startedAt 字段）
const loadError = ref('')
const loadedAt = ref(null)

const viewTab = ref('summary')
const viewTabs = [
  { key: 'summary', label: '摘要' },
  { key: 'dag', label: '执行过程' },
  { key: 'events', label: '事件流' },
  { key: 'claims', label: '结论与证据' },
  { key: 'report', label: '报告' },
  { key: 'meta', label: '运行详情' },
]

const dagRef = ref(null)
const abnormalOnly = ref(false)
const neighborFocus = ref(false)
const selectedTaskId = ref(null)

// 事件流筛选状态
const eventScope = ref('all') // all=全部 Run ｜ task=仅当前任务
const errorsOnly = ref(false)
const typeFilter = ref(null)
const search = ref('')
const expandedSeq = ref(null)
const openClaims = reactive(new Set())

const run = computed(() => detail.value?.run)
const tasks = computed(() => detail.value?.tasks ?? [])
const edges = computed(() => detail.value?.edges ?? [])
const claims = computed(() => detail.value?.claims ?? [])
const runActive = computed(() => ['QUEUED', 'RUNNING', 'REPORTING'].includes(run.value?.status))

// ===== 报告 tab（GET /api/rca-runs/{id}/report；三态 NONE/OK/REJECTED，懒加载 + 失败重试）=====
const report = ref(null)
const reportLoadState = ref('idle') // idle | loading | ok | error
const reportError = ref('')
const reportPkgOpen = ref(false) // REJECTED 原文折叠

async function loadReport() {
  reportLoadState.value = 'loading'
  reportError.value = ''
  try {
    report.value = await api(`/rca-runs/${route.params.runId}/report`)
    reportLoadState.value = 'ok'
  } catch (e) {
    reportLoadState.value = 'error'
    reportError.value = e?.response?.data?.error
      ? `报告加载失败：${e.response.data.error}`
      : '报告加载失败（后端不可达或接口未部署）'
  }
}

// packageJson：后端可解析 → JSON 树内联；畸形原文（REJECTED_MALFORMED）→ 原样字符串，前端再兜底一次
const reportPkg = computed(() => {
  const p = report.value?.packageJson
  if (p && typeof p === 'object') return p
  if (typeof p === 'string') { try { return JSON.parse(p) } catch { return null } }
  return null
})
const reportPkgRaw = computed(() => {
  const p = report.value?.packageJson
  if (p == null) return ''
  return typeof p === 'string' ? p : JSON.stringify(p, null, 2)
})

// 六段式键序（v1/v2 共享六段；claims 为 v2 类型化断言附加段，缺省不渲染）
const REPORT_SECTION_DEFS = [
  ['summary', '摘要'],
  ['root_cause', '根因'],
  ['evidence', '证据'],
  ['impact', '影响'],
  ['remediation', '处置建议'],
  ['references', '参考引用'],
  ['claims', '类型化断言'],
]
const reportSections = computed(() => {
  const pkg = reportPkg.value
  if (!pkg || typeof pkg !== 'object') return []
  const out = []
  for (const [key, title] of REPORT_SECTION_DEFS) {
    const v = pkg[key]
    if (v == null) continue
    if (typeof v === 'string') out.push({ key, title, kind: 'text', value: v })
    else if (Array.isArray(v)) out.push({ key, title, kind: 'list', items: v })
    else out.push({ key, title, kind: 'object', value: v })
  }
  return out
})

// usageMissing=true → 「用量未回报」，不显 0 冒充
const reportHeadKv = computed(() => {
  const r = report.value
  if (!r || r.state === 'NONE') return {}
  return {
    '报告 ID': r.reportId,
    '生成时间': fmtTime(r.createdAt),
    'schema 版本': `v${r.schemaVersion}`,
    '验证状态': r.validationStatus,
    '模型': r.model ?? '—',
    'token（入/出/合计）': r.usageMissing
      ? '用量未回报'
      : `${r.promptTokens ?? '—'} / ${r.completionTokens ?? '—'} / ${r.totalTokens ?? '—'}`,
  }
})

const PUB_STATE_ZH = {
  PENDING: '待发布', READY: '待投递', SENT: '已外发',
  RETRY_WAIT: '重试中', DEAD: '外发失败', SUPPRESSED: '已抑制（不外发）',
}
const pubStateText = computed(() =>
  PUB_STATE_ZH[report.value?.publication?.state] ?? report.value?.publication?.state ?? '')
const pubTagType = computed(() => ({
  SENT: 'success', DEAD: 'danger', RETRY_WAIT: 'warning',
}[report.value?.publication?.state] ?? 'info'))
// lastError 为 jsonb 原文（字符串）：可解析取 message/error 字段，否则原文展示
const pubLastError = computed(() => {
  const raw = report.value?.publication?.lastError
  if (!raw) return ''
  try {
    const o = JSON.parse(raw)
    return o?.message ?? o?.error ?? raw
  } catch { return raw }
})

// 任务规范化：真投影 {id:taskKey, taskId, status, priority, deadline, lease, attempts:次数}；
// name 供 DAG 节点显示，deps/downstream 由 edges 推导
const dagTasks = computed(() => tasks.value.map(t => ({
  ...t,
  name: t.name ?? t.id,
  deps: edges.value.filter(e => e.target === t.id).map(e => taskOf(e.source)).filter(Boolean),
  downstream: edges.value.filter(e => e.source === t.id).map(e => taskOf(e.target)).filter(Boolean),
})))
const selectedTask = computed(() => dagTasks.value.find(t => t.id === selectedTaskId.value) ?? null)

function taskOf(id) {
  const t = tasks.value.find(x => x.id === id)
  return t ? { ...t, name: t.name ?? t.id } : null
}

const statusStyle = STATUS_STYLE
const statusOrder = STATUS_ORDER

// ===== SSE：REST 游标种子 + ticket 换票开流 =====
const liveEvents = ref([])
const revision = ref(null) // rca_run.last_event_seq 锚（命令 expectedRevision）
let es = null
let esRetryTimer = null

const allEvents = computed(() => [...(detail.value?.events ?? []), ...liveEvents.value])
const lastSeq = computed(() => allEvents.value.reduce((m, e) => Math.max(m, e.seq), 0))
const eventTypes = computed(() => [...new Set(allEvents.value.map(e => e.type))])
const recentEvents = computed(() => [...allEvents.value].sort((a, b) => b.seq - a.seq).slice(0, 5))

function appendLive(row) {
  if (typeof row?.seq !== 'number') return
  if (allEvents.value.some(e => e.seq === row.seq)) return
  liveEvents.value.push(row)
  liveEvents.value.sort((a, b) => a.seq - b.seq)
  revision.value = Math.max(revision.value ?? 0, row.seq)
}

async function seedEvents(runId) {
  const page = await api(`/rca-runs/${runId}/events`, { params: { after_seq: 0, limit: 200 } })
  detail.value.events = page.events ?? []
  if (typeof page.latestSeq === 'number') revision.value = page.latestSeq
}

function closeStream() {
  clearTimeout(esRetryTimer)
  esRetryTimer = null
  if (es) { es.close(); es = null }
  sse.setDisconnected()
}

function openStream(runId, delayMs = 800) {
  closeStream()
  sse.setConnecting()
  esRetryTimer = setTimeout(async () => {
    try {
      const { ticket } = await api(`/rca-runs/${runId}/events/stream-ticket`, { method: 'POST' })
      es = new EventSource(`/api/rca-runs/${runId}/events/stream?ticket=${encodeURIComponent(ticket)}`)
      es.onopen = () => sse.setConnected()
      es.onmessage = ev => {
        try {
          appendLive(JSON.parse(ev.data))
          sse.markEvent()
        } catch { /* 心跳/非 JSON 帧忽略 */ }
      }
      // 服务端判定客户端游标过旧：停增量、全量重同步后重新开流
      es.addEventListener('resync', async () => {
        closeStream()
        try { await seedEvents(runId) } finally { openStream(runId) }
      })
      // 票 TTL 30s 单次：浏览器自动重连带旧票必败——统一关流、重新换票开流
      es.onerror = () => { sse.setDisconnected(); openStream(runId) }
    } catch {
      openStream(runId, 3000)
    }
  }, delayMs)
}

const sseStatusText = computed(() => ({
  connected: '实时 已连接', connecting: '实时 连接中', disconnected: '实时 已断开',
}[sse.status] ?? '实时 已断开'))

// ===== Transcript：新事件不打断滚动 =====
const evListRef = ref(null)
const newCount = ref(0)
let nearBottom = true

function onEvScroll() {
  const el = evListRef.value
  if (!el) return
  nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (nearBottom) newCount.value = 0
}

function scrollEvToBottom() {
  const el = evListRef.value
  if (el) el.scrollTop = el.scrollHeight
  newCount.value = 0
  nearBottom = true
}

watch(() => liveEvents.value.length, () => {
  if (viewTab.value !== 'events') return
  if (nearBottom) nextTick(scrollEvToBottom)
  else newCount.value++
})

const filteredEvents = computed(() => allEvents.value.filter(e => {
  if (eventScope.value === 'task' && e.taskId !== selectedTask.value?.taskId) return false
  if (errorsOnly.value && e.level !== 'error') return false
  if (typeFilter.value && e.type !== typeFilter.value) return false
  const q = search.value.trim().toLowerCase()
  if (q && !e.type.toLowerCase().includes(q) && !String(e.seq).includes(q)) return false
  return true
}))

function eventZh(type) { return zh(EVENT_TYPE_ZH, type) }
function taskNameOf(ev) {
  if (!ev.taskId) return null
  return tasks.value.find(t => t.taskId === ev.taskId)?.id ?? null
}

// ===== Claim 三层分级：证据 / 假设 / 结论（A4：真值四枚举精确匹配，删 mock 时代中英混排正则）=====
const claimGroups = computed(() => {
  const groups = { conclusion: [], hypothesis: [], evidence: [] }
  for (const c of claims.value) {
    if (c.kind === 'SYMPTOM') groups.evidence.push(c)
    else if (c.kind === 'HYPOTHESIS') groups.hypothesis.push(c)
    else groups.conclusion.push(c) // ROOT_CAUSE / EXCLUSION / null / 未知 → 结论组（保旧兜底语义）
  }
  return [
    { key: 'conclusion', title: '结论', items: groups.conclusion, emptyText: '暂无结论——原因待确认' },
    { key: 'hypothesis', title: '假设', items: groups.hypothesis, emptyText: '暂无假设' },
    { key: 'evidence', title: '证据', items: groups.evidence, emptyText: '暂无证据' },
  ]
})
const currentConclusion = computed(() =>
  claims.value.find(c => c.kind === 'ROOT_CAUSE' && c.current) ?? null)
const pendingHypotheses = computed(() =>
  claims.value.filter(c => /hypoth|假设/i.test(String(c.kind ?? '')) && !/validated|confirmed/i.test(String(c.verdict ?? ''))))

function toggleClaim(id) {
  openClaims.has(id) ? openClaims.delete(id) : openClaims.add(id)
}

// ===== 运行详情 =====
const runHeadKv = computed(() => ({
  '调查 ID': run.value?.id,
  '告警 ID': run.value?.incident,
  '状态': `${run.value?.status}（${listRow.value?.stageZh ?? '—'}）`,
  '严重度': run.value?.severity ?? '投影未提供',
  '引擎': run.value?.engine ?? '—',
  '配置摘要': run.value?.config ?? '—',
  '负责人': '认领面未落码',
  '事件游标（revision）': revision.value ?? '—',
}))

// ===== §三.5 费用（usage 块 = rca_model_call 聚合，RV08 口径；无行 → null 显「无模型调用」）=====
const usage = computed(() => detail.value?.usage ?? null)

function currencySymbol(currency) {
  return { CNY: '¥', USD: '$', EUR: '€' }[currency] ?? (currency ? `${currency} ` : '')
}

function formatCost(u) {
  if (u?.costMicros == null) return null // 未定价/未结算——不显示 ¥0 冒充
  return currencySymbol(u.currency) + (u.costMicros / 1_000_000).toFixed(4)
}

const costText = computed(() => {
  const u = usage.value
  if (!u) return '无模型调用'
  const cost = formatCost(u)
  if (cost == null) return '未定价'
  return u.usageMissing > 0 ? `${cost}（下限）` : cost
})

const costTitle = computed(() => {
  const u = usage.value
  if (!u) return '本调查无模型调用账本记录'
  const base = `模型调用 ${u.callCount} 次｜token 入 ${u.tokensIn} / 出 ${u.tokensOut}`
  return u.usageMissing > 0 ? `${base}｜${u.usageMissing} 笔调用用量未回报，费用为下限` : base
})

const usageKv = computed(() => {
  const u = usage.value
  if (!u) return {}
  return {
    '调用次数': u.callCount,
    '输入 token': u.tokensIn,
    '输出 token': u.tokensOut,
    '费用': formatCost(u) ?? '未定价/未结算',
    '币种': u.currency ?? '未知',
    '定价版本': u.pricingVersion ?? '未知',
    '用量未回报': u.usageMissing > 0 ? `${u.usageMissing} 笔（费用为下限）` : '0',
  }
})

// ===== §三.5 角色与轮次（V46 绑定投影；旧 run 全 null → 「旧版单角色执行」降级）=====
const hasRoleData = computed(() => tasks.value.some(t => t.roleId != null))

// 串行/并发推断依据：同一上游任务在同一轮次的多个下游 = 并发分支；链式单下游 = 串行
const roleRows = computed(() => {
  const roundOfKey = key => tasks.value.find(x => x.id === key)?.roundId ?? 0
  return dagTasks.value.map(t => {
    const upstreams = edges.value.filter(e => e.target === t.id).map(e => e.source)
    const siblingCount = edges.value.filter(e =>
      e.target !== t.id && upstreams.includes(e.source) &&
      roundOfKey(e.target) === (t.roundId ?? 0)).length
    let orchestration
    if (!upstreams.length) orchestration = '入口'
    else if (siblingCount) orchestration = `并发分支（同上游 ${siblingCount + 1} 路）`
    else orchestration = '串行'
    return { ...t, orchestration }
  })
})

// ===== 干预命令（真端点：幂等键 + expectedRevision=事件游标） =====
const cmdPending = ref(false)

async function submitCommand(type, payload = {}) {
  if (revision.value == null) {
    ElMessage.warning('事件游标尚未就绪，请稍后重试')
    return
  }
  cmdPending.value = true
  try {
    const res = await api(`/rca-runs/${route.params.runId}/commands`, {
      method: 'POST',
      body: {
        type,
        idempotencyKey: crypto.randomUUID(),
        expectedRevision: revision.value,
        payload,
      },
    })
    ElMessage.success(res.state === 'APPLIED' ? '命令已生效' : '命令已受理')
    await reload()
  } catch (e) {
    const st = e?.response?.status
    if (st === 409) {
      ElMessage.warning('调查状态已变化（修订号过期），已为你刷新')
      await reload()
    } else if (st === 403) {
      ElMessage.error('终态调查不接受该命令')
    } else {
      ElMessage.error('命令提交失败：' + (e?.response?.data?.error ?? '网络异常'))
    }
  } finally {
    cmdPending.value = false
  }
}

async function submitCancel() {
  try {
    await ElMessageBox.confirm('取消后进行中的任务将被终止，该操作会留痕审计。', '取消调查', {
      type: 'warning', confirmButtonText: '取消调查', cancelButtonText: '保留',
    })
  } catch { return }
  submitCommand('CANCEL')
}

async function submitHint() {
  let text
  try {
    const { value } = await ElMessageBox.prompt(
      '线索将以 UNTRUSTED（不可信输入）身份进入调查上下文，仅作参考，不会被当作事实。',
      '补充线索',
      { inputPlaceholder: '例如：昨晚 22:00 有发布变更', confirmButtonText: '提交', cancelButtonText: '取消' },
    )
    text = value?.trim()
  } catch { return }
  if (text) submitCommand('HINT', { text })
}

async function submitFeedback() {
  let text
  try {
    const { value } = await ElMessageBox.prompt('对该调查的报告质量反馈（留痕审计）。', '报告反馈', {
      inputPlaceholder: '例如：结论与证据一致，可以发布', confirmButtonText: '提交', cancelButtonText: '取消',
    })
    text = value?.trim()
  } catch { return }
  if (text) submitCommand('FEEDBACK', { text })
}

// ===== EV-10 配置切换（改造方案 §4.4 / 体验方案 §4.3：高级操作「在安全点更新此调查」）=====
// 读面 = GET /api/rca-runs/{id}/config-epochs（RunConfigEpochController，OPERATOR 矩阵）；
// 写面 = POST .../commands type=CONFIG_SWITCH（RunCommandController:73 接线 EN-04 切换服务）。
// 诚实面：提交返回的 WAITING_SAFE_POINT（202）只是受理，生效以 epoch 历史出现
// source_command_id 匹配的新行为准；EXPIRED 无命令状态查询端点，期限过后如实标注。
const cfgEpochs = ref({})
const cfgState = ref('idle') // idle | loading | ok | not-ready | error
const cfgLoading = ref(false)
const cfgNotReadyText = ref('')

const cfgMixed = computed(() => !!cfgEpochs.value.mixed_config)
const cfgCurrentEpoch = computed(() => {
  const list = cfgEpochs.value.epochs ?? []
  return list.length ? list[list.length - 1].config_epoch : null
})
const cfgCurrentDigest = computed(() => {
  const list = cfgEpochs.value.epochs ?? []
  return list.length ? list[list.length - 1].release_digest : null
})

// 仅 RUNNING 可点（服务端对非活跃 Run 一律 REJECTED_FORBIDDEN）；无代际史 = 存量 run
// 服务端 fail-closed；权限不在前端裁定，403 由服务端返回后如实展示
const canCfgSwitch = computed(() =>
  run.value?.status === 'RUNNING' && cfgState.value === 'ok'
  && (cfgEpochs.value.epochs ?? []).length > 0 && !cmdPending.value && !switchPending.value)
const cfgSwitchHint = computed(() => {
  if (cfgState.value !== 'ok') return ''
  if (run.value?.status !== 'RUNNING') return `仅进行中（RUNNING）的调查可发起安全点热切；当前状态 ${run.value?.status ?? '—'}`
  if (!(cfgEpochs.value.epochs ?? []).length) return '该调查无配置代际史（EN-04 前铸造的存量 run），服务端不支持热切'
  if (!canCfgSwitch.value) return ''
  return ''
})

async function loadCfgEpochs() {
  cfgLoading.value = true
  try {
    cfgEpochs.value = await getRunConfigEpochs(route.params.runId)
    cfgState.value = 'ok'
  } catch (e) {
    cfgState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (cfgState.value === 'not-ready') {
      cfgNotReadyText.value = e.status === 403
        ? '当前账号无权访问（HTTP 403）：配置代际查询沿 /api/rca-runs/** OPERATOR 权限矩阵。'
        : '接口不存在（HTTP 404）：后端未以 docker profile 部署 RunConfigEpochController（EN-10）。'
    }
  } finally {
    cfgLoading.value = false
  }
}

// ----- 热切对话框 -----
const switchVisible = ref(false)
const switchBundles = ref([])
const switchBundlesLoading = ref(false)
const switchBundlesError = ref('')
const switchTarget = ref('')
const switchReason = ref('')
const switchPending = ref(false)
const switchCmd = ref(null) // {commandId, state, reason, targetDigest, targetEpoch, deadlineAt}
let cfgPollTimer = null

const SWITCH_WAIT_MS = 15 * 60 * 1000 // §227 命令等待安全点期限（命令自身 deadline，非 Run 期限）

async function openSwitchDialog() {
  switchVisible.value = true
  switchTarget.value = ''
  switchReason.value = ''
  switchBundlesError.value = ''
  switchBundlesLoading.value = true
  try {
    const d = await listBundles()
    switchBundles.value = d.items ?? []
  } catch (e) {
    switchBundlesError.value = e instanceof ApiNotReadyError
      ? `版本清单接口不可用（${e.message}）——无法选择目标版本，未提交任何命令`
      : '版本清单加载失败（网络或服务端错误），未提交任何命令'
  } finally {
    switchBundlesLoading.value = false
  }
}

const switchCmdStateText = computed(() => ({
  WAITING_SAFE_POINT: '等待安全点',
  APPLIED: '已应用',
  REJECTED: '已拒绝',
  EXPIRED: '已过期（等待期限内未达安全点）',
}[switchCmd.value?.state] ?? switchCmd.value?.state))

function stopCfgPoll() {
  clearInterval(cfgPollTimer)
  cfgPollTimer = null
}

// WAITING 后无命令状态查询端点（EN-10 仅暴露 epoch 历史）——轮询 epoch 史，
// 出现 source_command_id 匹配的新行 = 已应用；过命令期限未出现 = 如实按过期展示
function startCfgPoll() {
  stopCfgPoll()
  cfgPollTimer = setInterval(async () => {
    const cmd = switchCmd.value
    if (!cmd || cmd.state !== 'WAITING_SAFE_POINT') { stopCfgPoll(); return }
    if (Date.now() >= cmd.deadlineAt || !runActive.value) {
      switchCmd.value = { ...cmd, state: 'EXPIRED', reason: runActive.value
        ? '已过命令等待期限仍未应用（EXPIRED 翻牌由后端巡回任务执行，以 epoch 历史为准）'
        : '调查已离开活动态，等待中的切换命令不会再应用' }
      stopCfgPoll()
      return
    }
    try {
      const d = await getRunConfigEpochs(route.params.runId)
      cfgEpochs.value = d
      const hit = (d.epochs ?? []).find(r => r.source_command_id === cmd.commandId)
      if (hit) {
        switchCmd.value = { ...cmd, state: 'APPLIED', reason: null }
        stopCfgPoll()
        await reload()
      }
    } catch { /* 轮询失败不改动状态，下一轮再试 */ }
  }, 5000)
}

async function submitSwitch() {
  const target = switchTarget.value
  const reason = switchReason.value.trim()
  if (!target || !reason || switchPending.value) return
  if (revision.value == null || cfgCurrentEpoch.value == null) {
    ElMessage.warning('事件游标或配置代际尚未就绪，请刷新后重试')
    return
  }
  switchPending.value = true
  const deadlineAt = Date.now() + SWITCH_WAIT_MS
  try {
    const res = await api(`/rca-runs/${route.params.runId}/commands`, {
      method: 'POST',
      body: {
        type: 'CONFIG_SWITCH',
        // 幂等锚 (run_id, CONFIG_SWITCH, idempotency_key)：runId+目标 digest 稳定可重放
        idempotencyKey: `cfg-switch-${route.params.runId}-${target}`,
        expectedRevision: revision.value,
        payload: {
          expected_config_epoch: cfgCurrentEpoch.value,
          target_release_digest: target,
          reason,
          deadline: new Date(deadlineAt).toISOString(),
        },
      },
    })
    switchVisible.value = false
    switchCmd.value = {
      commandId: res.commandId, state: res.state, reason: res.reason ?? null,
      targetDigest: target, targetEpoch: cfgCurrentEpoch.value + 1, deadlineAt,
    }
    if (res.state === 'WAITING_SAFE_POINT') {
      ElMessage.success(`切换命令已受理（等待安全点）${res.reason ? '：' + res.reason : ''}——生效以 epoch 历史为准`)
      startCfgPoll()
    } else if (res.state === 'APPLIED') {
      ElMessage.success('切换已应用——epoch 历史已追加新行')
      await loadCfgEpochs()
    } else {
      ElMessage.warning(`命令状态 ${res.state}${res.reason ? '：' + res.reason : ''}`)
    }
  } catch (e) {
    const st = e?.response?.status
    const data = e?.response?.data ?? {}
    const why = data.reason || data.error || ''
    // 快败拒绝（REJECTED_*/EXPIRED 随 4xx 同步返回）同样记入面板状态，如实显示「已拒绝+原因」
    if (data.commandId && data.state && data.state !== 'WAITING_SAFE_POINT') {
      switchCmd.value = {
        commandId: data.commandId, state: 'REJECTED', reason: why || data.state,
        targetDigest: target, targetEpoch: cfgCurrentEpoch.value + 1, deadlineAt,
      }
    }
    if (st === 403) {
      // 两种 403 同源展示：安全链（无 OPERATOR 角色）或命令面 REJECTED_FORBIDDEN（终态/资格/兼容拒绝）
      ElMessage.error(`服务端拒绝（403）：${why || '无 OPERATOR 权限或调查已终态'}——未产生任何变更`)
    } else if (st === 409) {
      ElMessage.warning(`修订/代际已变化（409）${why ? '：' + why : ''}——已为你刷新，请确认后重试`)
      await reload()
      await loadCfgEpochs()
    } else if (st === 400) {
      ElMessage.error(`命令参数被服务端拒绝：${why || '请检查目标版本与原因'}`)
    } else {
      ElMessage.error(`切换命令提交失败：${why || '网络异常'}`)
    }
  } finally {
    switchPending.value = false
  }
}

// ===== 交互 =====
function switchTab(key) {
  viewTab.value = key
  if (key === 'events') nextTick(scrollEvToBottom)
  if (key === 'meta' && cfgState.value === 'idle') loadCfgEpochs() // 配置切换区懒加载
  if (key === 'report' && reportLoadState.value === 'idle') loadReport() // 报告面懒加载
}

function onSelectTask(id) {
  selectedTaskId.value = id
  if (id) eventScope.value = 'task'
}

function shortId(id) { return id ? String(id).slice(0, 8) : '—' }

function copyTaskLink() {
  const url = `${location.origin}/runs/${route.params.runId}?task=${selectedTaskId.value}`
  navigator.clipboard?.writeText(url).then(
    () => ElMessage.success('任务链接已复制'),
    () => ElMessage.info(`任务链接：${url}`),
  )
}

function viewTaskEvents() {
  eventScope.value = 'task'
  switchTab('events')
}

function downloadEvents() {
  const blob = new Blob([JSON.stringify(filteredEvents.value, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${route.params.runId}-events-whitelist.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

async function loadDetail() {
  loadError.value = ''
  try {
    detail.value = await api(`/rca-runs/${route.params.runId}`)
    loadedAt.value = new Date().toISOString()
  } catch (e) {
    loadError.value = e?.response?.data?.error
      ? `调查加载失败：${e.response.data.error}`
      : '调查加载失败（后端不可达或参数非法）'
    return
  }
  // 耗时/卡点：详情投影无时间字段，从队列投影行取（失败不阻塞）
  api('/rca-runs').then(d => {
    listRow.value = (d.rows ?? []).find(r => r.id === route.params.runId) ?? null
  }).catch(() => { listRow.value = null })
}

async function reload() {
  await loadDetail()
  if (detail.value) {
    try { await seedEvents(route.params.runId) } catch { /* SSE resync 兜底 */ }
  }
}

onMounted(async () => {
  await loadDetail()
  if (!detail.value) return
  // 默认选中运行中任务
  selectedTaskId.value =
    dagTasks.value.find(t => t.status === 'RUNNING')?.id ?? dagTasks.value[0]?.id ?? null
  try { await seedEvents(route.params.runId) } catch { /* 种子失败不阻塞，SSE resync 兜底 */ }
  openStream(route.params.runId)
})

onBeforeUnmount(() => { closeStream(); stopCfgPoll() })
</script>

<style scoped>
.run-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.crumb {
  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px;
  font-size: var(--fs-aux); color: var(--ink-2);
}
.crumb .sep { color: var(--line-strong); margin: 0 6px; }

.runhead {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 12px var(--card-pad); font-size: var(--fs-body);
}
.inc-link { font-weight: 600; font-size: var(--fs-section); }
.mini { font-size: var(--fs-aux); color: var(--ink-2); }
.blocker { color: var(--warn); max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops { margin-left: auto; display: inline-flex; gap: 8px; flex-wrap: wrap; }

.tabs { display: flex; gap: 2px; padding: 4px 10px 0; }
.tab {
  border: none; background: none; cursor: pointer;
  padding: 8px 14px; font-size: var(--fs-body); color: var(--ink-2);
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab:hover { color: var(--brand); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.panel { padding: 14px var(--card-pad); }
.lbl { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 8px; }
.muted { color: var(--ink-2); font-size: var(--fs-body); }
.flex-spacer { flex: 1; }
.line-item { font-size: var(--fs-body); line-height: 1.9; }
.seq { color: var(--ink-2); font-size: var(--fs-aux); margin-right: 6px; }
.conclusion { font-size: var(--fs-body); }
.conclusion-pending { display: flex; align-items: center; gap: 10px; }
.progress-line { font-size: var(--fs-body); }
.blocker-box {
  margin-top: 8px; font-size: var(--fs-aux); color: var(--warn);
  background: var(--warn-bg); border: 1px solid #ecd9a0; border-radius: var(--radius-ctl); padding: 6px 10px;
}

.toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 12px; font-size: var(--fs-aux); color: var(--ink-2);
}
.tinfo { color: var(--ink-2); font-size: var(--fs-aux); }
.legend { width: 100%; border-collapse: collapse; font-size: var(--fs-aux); }
.legend th, .legend td { border: 1px solid var(--line); padding: 3px 8px; text-align: left; }
.legend th { background: #f0f2f5; color: var(--ink-2); }

.dag-card { padding: 12px; }
.dag-card :deep(.dag-canvas) { height: 560px; }
.note { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }

.box {
  border: 1px solid var(--line); border-radius: var(--radius); background: #fafbfd;
  padding: 8px 12px; font-size: var(--fs-body); margin-bottom: 10px; line-height: 1.8;
}
.drawer-ops { display: flex; gap: 8px; }

/* ===== 事件流 Transcript ===== */
.ev-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: var(--fs-aux); color: var(--ink-2);
  border: 1px solid var(--line); border-radius: var(--radius); background: #f4f6fa;
  padding: 8px 10px; margin-bottom: 10px;
}
.ev-toolbar .w-type { width: 170px; }
.ev-toolbar .w-search { width: 170px; }

.ev-scroll-wrap { position: relative; }
.new-bar {
  position: sticky; top: 0; z-index: 2;
  text-align: center; font-size: var(--fs-aux); color: var(--brand);
  background: var(--brand-soft); border: 1px solid #b3d8ff; border-radius: var(--radius-ctl);
  padding: 5px 10px; margin-bottom: 6px; cursor: pointer;
}
.new-bar:hover { background: #d9ecff; }
.fade-enter-active, .fade-leave-active { transition: opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.ev-list { max-height: 560px; overflow-y: auto; }
.ev-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 8px 12px;
  margin-bottom: 6px; background: #fff; cursor: pointer;
}
.ev-card:hover { border-color: var(--brand); }
.ev-card.lv-error { border-left: 3px solid var(--bad); }
.ev-card.lv-warn { border-left: 3px solid var(--sev-p2); }
.ev-card.lv-info { border-left: 3px solid var(--line-strong); }
.ev-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-body); }
.lv-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--line-strong); flex: none; }
.lv-error .lv-dot { background: var(--bad); }
.lv-warn .lv-dot { background: var(--sev-p2); }
.lv-info .lv-dot { background: var(--ok); }
.ev-name { flex: none; }
.ev-summary { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 46%; }
.ev-meta { margin-left: auto; font-size: var(--fs-aux); color: var(--ink-2); flex: none; }
.ev-detail {
  margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line);
}
.ev-detail pre {
  background: #f4f6fa; border: 1px solid var(--line); border-radius: var(--radius-ctl);
  padding: 8px 10px; font-size: var(--fs-aux); overflow-x: auto; margin-bottom: 6px;
}
.ev-empty { text-align: center; color: var(--ink-2); font-size: var(--fs-body); padding: 24px 0; }
.ev-status { margin-top: 10px; font-size: var(--fs-aux); color: var(--ink-2); }

.ev-refs { margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line); line-height: 1.8; }

/* ===== 报告 tab ===== */
.rpt-alert { margin: 10px 0; }
.rpt-text { font-size: var(--fs-body); line-height: 1.8; white-space: pre-wrap; }
.pkg-raw {
  background: #f4f6fa; border: 1px solid var(--line); border-radius: var(--radius-ctl);
  padding: 8px 10px; font-size: var(--fs-aux); overflow-x: auto; margin: 6px 0;
  white-space: pre-wrap; word-break: break-all;
}
.pub-lbl { margin-top: 12px; }
.pub-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

/* ===== EV-10 配置切换 ===== */
.cfg-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.cfg-hint { margin: -4px 0 8px; }
.cfg-cmd {
  margin-top: 10px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  border-top: 1px dashed var(--line); padding-top: 8px;
}
.dlg-note { line-height: 1.7; margin-bottom: 12px; }
.dlg-select { width: 100%; margin-bottom: 10px; }

.loading { height: 320px; }
</style>

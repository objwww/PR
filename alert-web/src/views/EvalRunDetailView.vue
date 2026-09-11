<template>
  <div class="run-detail">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b class="mono">{{ runId }}</b>
      <!-- R8 取消接线：仅执行中可点；受理=CANCELLING 非终态（L 模式恢复核验后才收口） -->
      <el-button
        v-if="pageState === 'ok' && (run?.facets?.executionState ?? run?.state) === 'RUNNING'"
        class="crumb-cancel" type="danger" plain size="small"
        :loading="cancelling" @click="confirmCancel"
      >取消实验</el-button>
    </div>

    <template v-if="pageState === 'ok'">
      <!-- 第一屏固定：身份/版本 + 执行状态 + 当前阶段 + 最后有效进展时间；质量摘要 ≤4 项 -->
      <div class="card panel head-panel">
        <div class="head-grid">
          <div class="head-item">
            <div class="hi-label">实验 ID</div>
            <div class="hi-value">
              <span class="mono break">{{ run.runId }}</span>
              <el-button size="small" text @click="copyText(run.runId, '实验 ID 已复制')">复制</el-button>
            </div>
          </div>
          <div class="head-item">
            <div class="hi-label">执行状态</div>
            <div class="hi-value"><StatusBadge :status="badgeState(run.state)" /></div>
          </div>
          <div class="head-item">
            <div class="hi-label">当前阶段</div>
            <div class="hi-value" :class="{ muted: !run.facets?.phase }">
              {{ fmtPhase(run.facets?.phase) }}
              <span v-if="run.facets?.stageEnteredAt" class="hi-sub">（{{ fmtTime(run.facets.stageEnteredAt) }} 进入）</span>
            </div>
          </div>
          <div class="head-item">
            <div class="hi-label">最后有效进展时间</div>
            <div class="hi-value" :class="{ muted: !run.facets?.lastProgressAt }">
              {{ run.facets?.lastProgressAt ? fmtTime(run.facets.lastProgressAt) : '未统计' }}
            </div>
          </div>
        </div>
        <div class="head-versions">
          名称 <b>{{ run.displayName ?? '未统计' }}</b>
          <span class="sep">·</span> 模式 <b>{{ run.mode ?? '未统计' }}</b>
          <span class="sep">·</span> 模型 <b>{{ run.model ?? '未统计' }}</b>
          <span class="sep">·</span> Prompt <b>{{ run.promptVersion ?? '未统计' }}</b>
          <span class="sep">·</span> 数据集 <b>{{ run.datasetVersion ?? '未统计' }}</b>
          <span class="sep">·</span> 已结清案例 <b>{{ fmtPair(run.caseCount, run.totalScenarios) }}</b>
        </div>
        <div class="quality-strip">
          <div class="qs-item">
            <div class="qs-label">端到端命中率</div>
            <div class="qs-value">{{ fmtRatioStatOr(run.quality?.endToEndHitRate, run.endToEndHitRate) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">条件准确率</div>
            <div class="qs-value">{{ fmtRatioStatOr(run.quality?.conditionalAccuracy, run.conditionalAccuracy) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">根因可判定覆盖率</div>
            <div class="qs-value">{{ fmtRatioStatOr(run.quality?.coverage, run.coverage) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">未决率</div>
            <div class="qs-value">{{ fmtRatioStatOr(run.quality?.unresolvedRate, run.unresolvedRate) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">错误确认率</div>
            <div class="qs-value">{{ fmtRatioStat(run.quality?.falseConfirmation) }}</div>
          </div>
        </div>

        <!-- EV-03 六状态分面：各面独立表达互不顶替；UNKNOWN/字段缺席一律“未统计”，tooltip 注明数据来源归属 -->
        <el-descriptions class="facets" :column="3" border size="small">
          <el-descriptions-item label="执行状态（executionState）">
            <StatusBadge :status="badgeState(run.facets?.executionState ?? run.state)" />
          </el-descriptions-item>
          <el-descriptions-item label="阶段（phase）">
            <span :class="{ muted: !run.facets?.phase }">{{ fmtPhase(run.facets?.phase) }}</span>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              质量门（qualityVerdict）
              <el-tooltip content="数据来源归 EV-07（质量门持久化）；当前后端未接线，恒 UNKNOWN" placement="top">
                <span class="facet-tip">?</span>
              </el-tooltip>
            </template>
            <span :class="{ muted: isUnknownFacet(run.facets?.qualityVerdict) }">{{ fmtFacet(run.facets?.qualityVerdict) }}</span>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              恢复（recoveryState）
              <el-tooltip content="数据来源归 EV-04（持久化 worker / L 模式恢复）；当前后端未接线，恒 UNKNOWN" placement="top">
                <span class="facet-tip">?</span>
              </el-tooltip>
            </template>
            <span :class="{ muted: isUnknownFacet(run.facets?.recoveryState) }">{{ fmtFacet(run.facets?.recoveryState) }}</span>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              用量（usageStatus）
              <el-tooltip content="数据来源归 EV-06（R7 RCA 调用账本显式接线）；RV08 红线——不读 PR 域账本冒充，当前恒 UNKNOWN" placement="top">
                <span class="facet-tip">?</span>
              </el-tooltip>
            </template>
            <span :class="{ muted: isUnknownFacet(run.facets?.usageStatus) }">{{ fmtFacet(run.facets?.usageStatus) }}</span>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              新鲜度（freshness）
              <el-tooltip content="投影同步直读主表，无滞后即为实时（LIVE）" placement="top">
                <span class="facet-tip">?</span>
              </el-tooltip>
            </template>
            <span :class="{ muted: isUnknownFacet(run.facets?.freshness) }">{{ fmtFacet(run.facets?.freshness) }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="asof muted">数据截至 {{ run.asOf ? fmtClock(run.asOf) : '未统计' }}</div>

        <!-- 配置详情默认折叠，完整 digest 不占首屏 -->
        <el-collapse class="cfg-collapse">
          <el-collapse-item title="配置详情（镜像 / 配置摘要、时间与症状计数）" name="cfg">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="镜像摘要（registry digest）">
                <span class="mono break">{{ run.registryDigest ?? '未统计' }}</span>
                <el-button v-if="run.registryDigest" size="small" text @click="copyText(run.registryDigest, '镜像摘要已复制')">复制</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="配置摘要（config digest）">
                <span class="mono break">{{ run.configDigest ?? '未统计' }}</span>
                <el-button v-if="run.configDigest" size="small" text @click="copyText(run.configDigest, '配置摘要已复制')">复制</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ fmtTime(run.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="结束时间">{{ fmtTime(run.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ fmtDuration(run.startedAt, run.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="症状 TP / FP / FN">{{ fmtTff(run) }}</el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 主区页签：案例结果 / 用量与对账（原“对比/成本”占位并入后者；对比走 /eval/compare） -->
      <div class="card tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="tab"
          :class="{ cur: tab === t.key }"
          @click="switchTab(t.key)"
        >{{ t.label }}</button>
      </div>

      <!-- 案例：verdict 筛选 + 游标分页 + 失败样本展开；复合 row-key 防同场景多轮串行 -->
      <div v-if="tab === 'cases'" class="card panel">
        <!-- EV-05 证据汇总：run 级引用分桶；接口未部署（403/404）整区诚实空态，不伪造计数 -->
        <div class="ev-summary">
          <div class="es-head">
            <span class="es-title">证据汇总</span>
            <span class="muted es-note">来源 GET /eval/runs/{runId}/evidence-summary（EV-05）</span>
            <el-button size="small" :loading="summaryLoading" @click="loadEvidenceSummary">刷新</el-button>
          </div>
          <template v-if="summaryState === 'ok'">
            <div class="es-strip">
              <div class="es-item">
                <div class="es-label">案例数</div>
                <div class="es-value">{{ fmtCount(summary?.caseCount) }}</div>
              </div>
              <div class="es-item">
                <div class="es-label">有报告案例</div>
                <div class="es-value">{{ fmtCount(summary?.casesWithReport) }}</div>
              </div>
              <div class="es-item">
                <div class="es-label">引用总数</div>
                <div class="es-value">{{ fmtCount(summary?.totalRefs) }}</div>
              </div>
              <div class="es-item">
                <div class="es-label">引用类型分桶（已解析）</div>
                <div class="es-value">
                  <template v-if="typeBuckets.length">
                    <el-tag v-for="b in typeBuckets" :key="b.type" size="small" class="es-tag" disable-transitions>{{ b.type }} × {{ b.count }}</el-tag>
                  </template>
                  <span v-else class="muted">未统计</span>
                </div>
              </div>
            </div>
            <el-table :data="summary?.cases ?? []" size="small" class="es-table">
              <el-table-column prop="scenarioId" label="场景" min-width="140" show-overflow-tooltip />
              <el-table-column prop="roundNo" label="轮次" width="60" align="right" />
              <el-table-column label="判定" width="130">
                <template #default="{ row }"><StatusBadge :status="row.verdict" /></template>
              </el-table-column>
              <el-table-column label="证据状态" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.status === 'NO_REPORT'" type="info" size="small" disable-transitions>无报告</el-tag>
                  <el-tag v-else-if="row.status === 'NO_REFS'" type="warning" size="small" disable-transitions>无证据引用</el-tag>
                  <span v-else-if="row.status === 'OK'">OK</span>
                  <span v-else class="muted">未统计</span>
                </template>
              </el-table-column>
              <el-table-column label="引用 总/已解析/未解析" width="170" align="right">
                <template #default="{ row }">{{ refTriple(row) }}</template>
              </el-table-column>
              <el-table-column label="支持/反对/未决" width="140" align="right">
                <template #default="{ row }">{{ sruTriple(row) }}</template>
              </el-table-column>
              <template #empty>
                <EmptyState kind="empty" description="该实验暂无案例证据汇总" />
              </template>
            </el-table>
          </template>
          <EmptyState v-else-if="summaryState === 'unavailable'" kind="empty"
            description="证据汇总依赖后端 EV-05（evidence-summary 接口），当前未部署；不展示推测计数。" />
          <EmptyState v-else-if="summaryState === 'error'" kind="error" @retry="loadEvidenceSummary" />
          <div v-else v-loading="true" class="es-loading" />
        </div>

        <div class="case-filter">
          <el-select v-model="verdict" class="w-verdict" placeholder="全部判定" clearable @change="applyVerdict">
            <el-option value="DECIDABLE" label="可判定（DECIDABLE）" />
            <el-option value="UNRESOLVED" label="未决（UNRESOLVED）" />
            <el-option value="STRUCTURE_REJECTED" label="结构失败（STRUCTURE_REJECTED）" />
            <el-option value="TIMEOUT_OR_ABSENT" label="超时 / 缺席（TIMEOUT_OR_ABSENT）" />
          </el-select>
          <el-button :loading="casesLoading" @click="loadCases">刷新</el-button>
        </div>
        <template v-if="casesState === 'ok'">
          <el-table :data="cases" v-loading="casesLoading" :row-key="caseKey">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div v-if="row.failureSample" class="fail-sample">
                  <div class="fs-label">失败样本</div>
                  <pre>{{ row.failureSample }}</pre>
                </div>
                <div v-else class="fs-none">无失败样本</div>
              </template>
            </el-table-column>
            <el-table-column prop="scenarioId" label="场景 ID（scenarioId）" min-width="160" show-overflow-tooltip />
            <el-table-column prop="roundNo" label="轮次" width="70" align="right" />
            <el-table-column label="判定" width="130">
              <template #default="{ row }">
                <StatusBadge :status="row.verdict" />
              </template>
            </el-table-column>
            <el-table-column label="根因命中" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.rootCauseHit === false" type="danger" disable-transitions>未命中</el-tag>
                <span v-else-if="row.rootCauseHit === true">命中</span>
                <span v-else class="muted">未统计</span>
              </template>
            </el-table-column>
            <el-table-column label="期望根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.expectedRootCause ?? '未统计' }}</template>
            </el-table-column>
            <el-table-column label="实际根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.actualRootCause ?? '未统计' }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="110" align="right">
              <template #default="{ row }">{{ row.latencyMs == null ? '未统计' : `${row.latencyMs} ms` }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" :disabled="!row.caseExecutionId"
                  :title="row.caseExecutionId ? '查看案例详情（判定 / 场景身份 / 证据 / 关联链）' : '该案例缺少 caseExecutionId，无法定位案例详情'"
                  @click="openCaseDetail(row)">详情</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState kind="empty" :description="verdict ? '当前判定筛选无案例' : '暂无案例'" />
            </template>
          </el-table>
          <div class="pager">
            <span class="muted">已加载 {{ cases.length }} 条</span>
            <el-button v-if="casesCursor" :loading="casesLoadingMore" @click="loadMoreCases">加载更多</el-button>
          </div>
        </template>
        <EmptyState v-else-if="casesState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="casesState === 'error'" kind="error" @retry="loadCases" />
        <div v-else v-loading="true" class="loading-box" />
      </div>

      <!-- 用量与对账：占位说明（合并原“对比/成本”占位；均未交付能力集中说明） -->
      <div v-else class="card panel">
        <EmptyState kind="empty" description="用量与对账依赖后端 EV-06（调用账本接线与 usage 投影），本批未交付。实验对比请从实验列表选中两条后进入对比工作台（/eval/compare）。" :image-size="160" />
      </div>
    </template>

    <EmptyState v-else-if="pageState === 'forbidden'" kind="forbidden" />
    <EmptyState v-else-if="pageState === 'error'" kind="error" @retry="loadRun" />
    <div v-else v-loading="true" class="loading-box card" />

    <!-- EV-05 案例详情抽屉：判定 / 场景身份 / 证据三分组 / 关联链；接口未部署（403/404）显式降级提示，不伪造证据 -->
    <DetailDrawer v-model="detailOpen" title="案例详情" :size="560">
      <div v-if="detailState === 'loading'" v-loading="true" class="cd-loading" />
      <EmptyState v-else-if="detailState === 'unavailable'" kind="empty"
        description="案例详情依赖后端 EV-05（案例详情接口），当前未部署；证据与关联链数据不可用，不展示推测内容。" />
      <EmptyState v-else-if="detailState === 'error'" kind="error" @retry="loadCaseDetail" />
      <div v-else-if="detail" class="case-detail">
        <!-- 判定 -->
        <div class="cd-head">
          <div class="cd-title">
            <StatusBadge :status="detail.verdict" />
            <span class="mono break">{{ detail.scenarioId ?? '未统计' }}</span>
            <span class="muted">第 {{ detail.roundNo }} 轮</span>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="根因命中">
              <el-tag v-if="detail.rootCauseHit === false" type="danger" disable-transitions>未命中</el-tag>
              <span v-else-if="detail.rootCauseHit === true">命中</span>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item label="耗时">{{ detail.latencyMs == null ? '未统计' : `${detail.latencyMs} ms` }}</el-descriptions-item>
            <el-descriptions-item label="期望根因">{{ detail.expectedRootCause ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="实际根因">{{ detail.actualRootCause ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="症状 TP / FP / FN">{{ fmtTff(detail) }}</el-descriptions-item>
            <el-descriptions-item label="选择策略版本">{{ detail.selectionPolicyVersion ?? '未统计' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 场景身份：resolved=false 三路（无匹配/歧义/HOLDOUT 不可见）接口不区分具体成因，如实并列说明 -->
        <div class="cd-section">
          <div class="cd-sec-title">场景身份</div>
          <el-descriptions v-if="detail.scenarioIdentity?.resolved" :column="1" border size="small">
            <el-descriptions-item label="案例键（caseKey）">{{ detail.scenarioIdentity.caseKey ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="数据集">
              {{ detail.scenarioIdentity.datasetName ?? '未统计' }}
              <span class="muted">（版本 {{ detail.scenarioIdentity.datasetVersion ?? '未统计' }}）</span>
            </el-descriptions-item>
            <el-descriptions-item label="场景族（scenarioFamilyId）">{{ detail.scenarioIdentity.scenarioFamilyId ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="分区（partitionClass）">{{ detail.scenarioIdentity.partitionClass ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="来源类别（sourceClass）">{{ detail.scenarioIdentity.sourceClass ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="有效期">
              {{ detail.scenarioIdentity.validFrom ? fmtTime(detail.scenarioIdentity.validFrom) : '未统计' }}
              ~
              {{ detail.scenarioIdentity.validTo ? fmtTime(detail.scenarioIdentity.validTo) : '未统计' }}
            </el-descriptions-item>
            <el-descriptions-item label="内容摘要（contentDigest）">
              <span class="mono break">{{ detail.scenarioIdentity.contentDigest ?? '未统计' }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <template v-else>
            <el-alert type="warning" :closable="false" show-icon
              title="场景身份未解析：可能为精确键无匹配、归属存在歧义或 HOLDOUT 不可见（接口不区分具体成因）；身份字段一律不展示，不做猜测。" />
            <div class="si-dv muted">数据集版本（run 直读）：{{ detail.scenarioIdentity?.datasetVersion ?? '未统计' }}</div>
          </template>
        </div>

        <!-- 关联链：rcaRunId 可点跳调查页、incidentId 可点跳告警详情；scoredReportId 无独立页面，如实展示文本 -->
        <div class="cd-section">
          <div class="cd-sec-title">关联链</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="调查（rcaRunId）">
              <template v-if="detail.linkage?.rcaRunId">
                <router-link :to="`/runs/${detail.linkage.rcaRunId}`" class="mono break">{{ detail.linkage.rcaRunId }}</router-link>
                <span v-if="detail.linkage.rcaRunState" class="lk-sub muted">（状态 {{ detail.linkage.rcaRunState }}）</span>
              </template>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item label="告警事件（incidentId）">
              <router-link v-if="detail.linkage?.incidentId" :to="`/alerts/${detail.linkage.incidentId}`" class="mono break">{{ detail.linkage.incidentId }}</router-link>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item label="评分报告（scoredReportId）">
              <span v-if="detail.linkage?.scoredReportId" class="mono break">{{ detail.linkage.scoredReportId }}</span>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item label="报告校验状态">{{ detail.linkage?.reportValidationStatus ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="报告模型">{{ detail.linkage?.reportModel ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="报告生成时间">{{ detail.linkage?.reportCreatedAt ? fmtTime(detail.linkage.reportCreatedAt) : '未统计' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 证据三分组：ReportClaim 三态直分；跨对象未解析引用灰态"引用未解析"，不跨对象读取 -->
        <div class="cd-section">
          <div class="cd-sec-title">证据（支持 / 反对 / 未决）</div>
          <template v-if="detail.evidence?.status === 'OK'">
            <div v-for="g in evidenceGroups" :key="g.key" class="ev-group">
              <div class="evg-title" :class="`evg-${g.key}`">{{ g.label }}（{{ g.items.length }}）</div>
              <template v-if="g.items.length">
                <div v-for="(c, i) in g.items" :key="i" class="claim">
                  <div class="claim-head">
                    <el-tag size="small" :type="g.tagType" disable-transitions>{{ c.claimType ?? '未统计' }}</el-tag>
                    <span class="claim-title">{{ claimTitle(c) }}</span>
                  </div>
                  <div v-if="c.refs?.length" class="ref-list">
                    <div v-for="(r, j) in c.refs" :key="j" class="ref-row" :class="{ unresolved: !r.resolved }">
                      <span class="mono break">{{ r.ref ?? '未统计' }}</span>
                      <span v-if="r.resolved" class="ref-meta">
                        {{ [r.evidenceType, r.source].filter(Boolean).join(' · ') || '未统计' }}
                        <span v-if="r.timeStart || r.timeEnd" class="muted">（{{ fmtTime(r.timeStart) }} ~ {{ fmtTime(r.timeEnd) }}）</span>
                      </span>
                      <span v-else class="ref-unresolved">引用未解析</span>
                    </div>
                  </div>
                  <div v-else class="ref-none muted">无证据引用</div>
                </div>
              </template>
              <div v-else class="evg-none muted">无</div>
            </div>
          </template>
          <el-alert v-else type="info" :closable="false" show-icon
            :title="evidenceStatusText(detail.evidence?.status)" />
        </div>

        <div class="asof muted">数据截至 {{ detail.asOf ? fmtClock(detail.asOf) : '未统计' }}</div>
      </div>
    </DetailDrawer>
  </div>
</template>

<script setup>
// 实验详情（/eval/runs/:runId）：固定首屏（身份/状态/阶段/进展时间）+ 六状态分面 + 折叠配置 + 案例 / 用量与对账页签。
// EV-01：复合 row-key（caseExecutionId，旧接口回退 runId+scenarioId+roundNo）；watch runId 取消旧请求、
// 清空案例缓存、请求序号防旧响应覆盖；null 显示“未统计”；“已加载 N 条”。
// EV-03 接线：displayName/mode/totalScenarios/quality（比率三件套，旧契约回退旧数值字段）/
// facets 六分面（UNKNOWN→未统计并注明归属）/asOf 数据截至。
// EV-05 接线：案例 tab 顶部证据汇总区 + 行内“详情”抽屉（判定/场景身份/证据三分组/关联链）；
// 两端点 403/404（后端未部署）→ 显式降级空态，不伪造证据/计数；NO_REPORT/NO_REFS 如实区分。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { fmtClock, fmtCount, fmtDuration, fmtFacet, fmtPair, fmtPhase, fmtRatioStat, fmtRatioStatOr, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const runId = computed(() => String(route.params.runId ?? ''))
const badgeState = s => ({ RUNNING: 'RUNNING', SUCCEEDED: 'COMPLETED', FAILED: 'FAILED' }[s] ?? s)

// 分面未接线/缺席（UNKNOWN/null）→ 弱化显示
const isUnknownFacet = v => v == null || v === 'UNKNOWN'

// RV02：优先 caseExecutionId；旧接口无该字段时回退 runId+scenarioId+roundNo，禁止数组下标
const caseKey = row =>
  row.caseExecutionId ?? `${runId.value}|${row.scenarioId}|${row.roundNo}`

const tabs = [
  { key: 'cases', label: '案例结果' },
  { key: 'usage', label: '用量与对账' },
]
const TAB_KEYS = tabs.map(t => t.key)
const str = v => (typeof v === 'string' ? v : '')
// 旧链接兼容：tab=overview/compare/cost 归并到现页签
function normalizeTab(v) {
  if (v === 'compare' || v === 'cost') return 'usage'
  if (v === 'overview') return 'cases'
  return TAB_KEYS.includes(v) ? v : 'cases'
}
const tab = ref(normalizeTab(str(route.query.tab)))

const run = ref(null)
const pageState = ref('loading') // loading | ok | error | forbidden

const verdict = ref(str(route.query.verdict))
const cases = ref([])
const casesCursor = ref(null)
const casesState = ref('loading')
const casesLoading = ref(false)
const casesLoadingMore = ref(false)
let casesLoaded = false

// EV-05 证据汇总 / 案例详情：独立状态机；403/404 = 接口未部署 → unavailable 诚实空态
const summary = ref(null)
const summaryState = ref('loading') // loading | ok | unavailable | error
const summaryLoading = ref(false)
let summaryLoaded = false

const detailOpen = ref(false)
const detail = ref(null)
const detailState = ref('loading') // loading | ok | unavailable | error
let detailCaseId = null

// RV04：每类请求一个 AbortController + 单调序号；runId 变化即取消/丢弃旧请求
let runSeq = 0
let casesSeq = 0
let summarySeq = 0
let detailSeq = 0
let runCtl = null
let casesCtl = null
let summaryCtl = null

async function loadRun() {
  const id = runId.value
  const seq = ++runSeq
  runCtl?.abort()
  const ctl = new AbortController()
  runCtl = ctl
  pageState.value = 'loading'
  try {
    const r = await api(`/eval/runs/${encodeURIComponent(id)}`, { signal: ctl.signal })
    if (seq !== runSeq || id !== runId.value) return // 旧响应不覆盖新实验
    run.value = r
    pageState.value = 'ok'
  } catch (e) {
    if (ctl.signal.aborted || seq !== runSeq) return
    pageState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  }
}

function caseParams(cursor) {
  const p = { limit: 50 }
  if (verdict.value) p.verdict = verdict.value
  if (cursor) p.cursor = cursor
  return p
}

async function loadCases() {
  const id = runId.value
  const seq = ++casesSeq
  casesCtl?.abort()
  const ctl = new AbortController()
  casesCtl = ctl
  casesState.value = 'loading'
  casesLoading.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/cases`, { params: caseParams(), signal: ctl.signal })
    if (seq !== casesSeq || id !== runId.value) return
    cases.value = d.items ?? []
    casesCursor.value = d.nextCursor ?? null
    casesState.value = 'ok'
    casesLoaded = true
  } catch (e) {
    if (ctl.signal.aborted || seq !== casesSeq) return
    casesState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    if (seq === casesSeq) casesLoading.value = false
  }
}

async function loadMoreCases() {
  if (!casesCursor.value) return
  const id = runId.value
  const seq = casesSeq // 必须与最近一次 loadCases 同代
  const cursor = casesCursor.value
  casesLoadingMore.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/cases`, { params: caseParams(cursor) })
    if (seq !== casesSeq || id !== runId.value) return // 旧页不拼接
    cases.value = cases.value.concat(d.items ?? [])
    casesCursor.value = d.nextCursor ?? null
  } catch {
    if (seq === casesSeq) ElMessage.error('加载更多失败，请重试')
  } finally {
    if (seq === casesSeq) casesLoadingMore.value = false
  }
}

function switchTab(key) {
  tab.value = key
  const query = { ...route.query }
  if (key === 'cases') delete query.tab
  else query.tab = key
  if (key !== 'cases') delete query.verdict
  router.replace({ query })
  if (key === 'cases') ensureCasesTabLoaded()
}

function ensureCasesTabLoaded() {
  if (!casesLoaded) loadCases()
  if (!summaryLoaded) loadEvidenceSummary()
}

// EV-05 run 证据汇总：按案例分桶；403/404（接口未部署）→ unavailable 诚实空态
async function loadEvidenceSummary() {
  const id = runId.value
  const seq = ++summarySeq
  summaryCtl?.abort()
  const ctl = new AbortController()
  summaryCtl = ctl
  summaryState.value = 'loading'
  summaryLoading.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/evidence-summary`, { signal: ctl.signal })
    if (seq !== summarySeq || id !== runId.value) return
    summary.value = d
    summaryState.value = 'ok'
    summaryLoaded = true
  } catch (e) {
    if (ctl.signal.aborted || seq !== summarySeq) return
    const st = e?.response?.status
    summaryState.value = st === 403 || st === 404 ? 'unavailable' : 'error'
  } finally {
    if (seq === summarySeq) summaryLoading.value = false
  }
}

// EV-05 案例详情：双键定位；行无 caseExecutionId 不入（按钮已禁用）；403/404 → 降级提示
function openCaseDetail(row) {
  if (!row.caseExecutionId) return
  detailCaseId = row.caseExecutionId
  detailOpen.value = true
  loadCaseDetail()
}

async function loadCaseDetail() {
  const id = runId.value
  const cid = detailCaseId
  if (!cid) return
  const seq = ++detailSeq
  detailState.value = 'loading'
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/cases/${encodeURIComponent(cid)}`)
    if (seq !== detailSeq || cid !== detailCaseId) return
    detail.value = d
    detailState.value = 'ok'
  } catch (e) {
    if (seq !== detailSeq) return
    const st = e?.response?.status
    detailState.value = st === 403 || st === 404 ? 'unavailable' : 'error'
  }
}

// 汇总分桶/三态计数：NO_REPORT = 无统计对象 → 计数一律“未统计”，不拿 0 冒充
const typeBuckets = computed(() =>
  Object.entries(summary.value?.byType ?? {}).map(([type, count]) => ({ type, count })))

function refTriple(row) {
  if (row.status === 'NO_REPORT') return '未统计'
  return `${fmtCount(row.totalRefs)} / ${fmtCount(row.resolvedRefs)} / ${fmtCount(row.unresolvedRefs)}`
}

function sruTriple(row) {
  if (row.status === 'NO_REPORT') return '未统计'
  return `${fmtCount(row.supportingRefs)} / ${fmtCount(row.refutingRefs)} / ${fmtCount(row.undeterminedRefs)}`
}

// 证据块非 OK 三态如实文案（EV-05 契约：不猜结构、不当空吞）
function evidenceStatusText(status) {
  return {
    NO_REPORT: '无评分报告：该判定形态（结构失败 / 超时缺席）不产生评分报告，无证据可列。',
    UNSUPPORTED_SCHEMA_VERSION: '报告包结构版本不受支持，不猜测其内容。',
    PACKAGE_UNPARSEABLE: '报告包读回形状校验失败，已显式标记（不当空数据处理）。',
  }[status] ?? '证据状态未统计'
}

// claim 摘要：组件 / 故障类型组合，缺则回退 claimType
function claimTitle(c) {
  return [c?.component, c?.faultType].filter(Boolean).join(' / ') || c?.claimType || '未统计'
}

const evidenceGroups = computed(() => {
  const ev = detail.value?.evidence ?? {}
  return [
    { key: 'supporting', label: '支持证据', tagType: 'success', items: ev.supporting ?? [] },
    { key: 'refuting', label: '反对证据', tagType: 'danger', items: ev.refuting ?? [] },
    { key: 'undetermined', label: '未决证据', tagType: 'info', items: ev.undetermined ?? [] },
  ]
})

function applyVerdict() {
  const query = { ...route.query, tab: 'cases' }
  if (verdict.value) query.verdict = verdict.value
  else delete query.verdict
  router.replace({ query })
  loadCases()
}

// TP/FP/FN 三态：全 null → 未统计；单项 null 单项显示“未统计”，真实 0 显示 0
function fmtTff(r) {
  if (r.tp == null && r.fp == null && r.fn == null) return '未统计'
  return `${fmtCount(r.tp)} / ${fmtCount(r.fp)} / ${fmtCount(r.fn)}`
}

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

// R8 取消接线：POST /api/eval/runs/{runId}/cancel；幂等键固定 cancel-<runId>
// （重复点击=同键重放 200，不产生第二条取消命令）；受理≠已取消——推进归
// worker 案例边界检查点，L 模式强制恢复核验后才到终态（后端契约原义）
const cancelling = ref(false)

async function confirmCancel() {
  try {
    await ElMessageBox.confirm(
      '取消受理后实验进入「取消中」：worker 在案例边界停止新增案例；L 模式须恢复核验通过才到终态。确认取消？',
      '取消实验',
      { confirmButtonText: '确认取消', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return // 用户放弃
  }
  cancelling.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(runId.value)}/cancel`, {
      method: 'POST',
      body: { idempotencyKey: `cancel-${runId.value}`, reason: '页面手动取消' },
    })
    ElMessage.success(d.replayed
      ? '取消此前已受理（幂等重放），实验处于「取消中」'
      : '取消已受理（CANCELLING）；推进与恢复核验归 worker，请以页面刷新为准')
    loadRun()
  } catch (e) {
    const st = e?.response?.status
    const msg = e?.response?.data?.error
    if (st === 409) ElMessage.warning(msg || 'run 已终态，取消非法迁移')
    else if (st === 404) ElMessage.error('实验不存在（可能尚未被 worker 领取落库）')
    else ElMessage.error(msg || '取消请求失败，请重试')
  } finally {
    cancelling.value = false
  }
}

// 浏览器前进/后退：query 回灌（EU05）
watch(() => route.query, q => {
  const nextTab = normalizeTab(str(q.tab))
  if (nextTab !== tab.value) {
    tab.value = nextTab
    if (nextTab === 'cases') ensureCasesTabLoaded()
  }
  const nextVerdict = str(q.verdict)
  if (nextVerdict !== verdict.value) {
    verdict.value = nextVerdict
    if (tab.value === 'cases') loadCases()
  }
})

// RV04：同组件切换到另一实验——取消在飞请求、清空案例缓存、按新身份重载
watch(runId, (id, old) => {
  if (!id || id === old) return
  runCtl?.abort()
  casesCtl?.abort()
  summaryCtl?.abort()
  runSeq++
  casesSeq++
  summarySeq++
  detailSeq++
  casesLoaded = false
  summaryLoaded = false
  cases.value = []
  casesCursor.value = null
  casesState.value = 'loading'
  summary.value = null
  summaryState.value = 'loading'
  detailOpen.value = false
  detail.value = null
  detailCaseId = null
  run.value = null
  loadRun()
  if (tab.value === 'cases') ensureCasesTabLoaded()
})

onMounted(() => {
  loadRun()
  if (tab.value === 'cases') ensureCasesTabLoaded()
})
</script>

<style scoped>
.run-detail { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb-cancel { margin-left: auto; }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.mono { font-family: var(--mono, monospace); }
.break { word-break: break-all; }
.muted { color: var(--ink-2); }

.panel { padding: var(--card-pad); }

.head-panel { display: flex; flex-direction: column; gap: 16px; }
.head-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px 24px; }
.hi-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 2px; }
.hi-value { font-size: 15px; }
.hi-sub { font-size: var(--fs-aux); color: var(--ink-2); }
.head-versions { font-size: var(--fs-body); color: var(--ink-2); }
.head-versions b { color: var(--ink); font-weight: 600; }
.head-versions .sep { margin: 0 8px; color: var(--line-strong); }

.quality-strip { display: flex; gap: 24px; flex-wrap: wrap; border-top: 1px solid var(--line); padding-top: 12px; }
.qs-label { font-size: var(--fs-aux); color: var(--ink-2); }
.qs-value { font-size: 18px; font-weight: 600; color: var(--head); }

.facets { border-top: 1px solid var(--line); padding-top: 12px; }
.facet-tip {
  display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; margin-left: 4px; border-radius: 50%;
  border: 1px solid var(--line-strong); color: var(--ink-2);
  font-size: 10px; line-height: 1; cursor: help; vertical-align: 1px;
}
.asof { font-size: var(--fs-aux); text-align: right; }

.cfg-collapse { border-top: 1px solid var(--line); }
.cfg-collapse :deep(.el-collapse-item__header) { font-size: var(--fs-body); }

.tabs { display: flex; gap: 2px; padding: 4px 6px; overflow-x: auto; }
.tab {
  border: none; background: none; font-family: inherit; white-space: nowrap;
  padding: 7px 14px; font-size: 13px; color: var(--ink-2);
  border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; cursor: pointer;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.case-filter { display: flex; gap: 8px; margin-bottom: 12px; }
.case-filter .w-verdict { width: 260px; }

/* EV-05 证据汇总 */
.ev-summary { border: 1px solid var(--line); border-radius: var(--radius-ctl); padding: 12px 14px; margin-bottom: 14px; }
.es-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.es-head .el-button { margin-left: auto; }
.es-title { font-size: var(--fs-body); font-weight: 600; color: var(--head); }
.es-note { font-size: var(--fs-aux); }
.es-strip { display: flex; gap: 28px; flex-wrap: wrap; margin-bottom: 10px; }
.es-label { font-size: var(--fs-aux); color: var(--ink-2); }
.es-value { font-size: 16px; font-weight: 600; color: var(--head); }
.es-tag { margin-right: 6px; }
.es-table { width: 100%; }
.es-loading { height: 120px; }

/* EV-05 案例详情抽屉 */
.cd-loading { height: 240px; }
.case-detail { display: flex; flex-direction: column; gap: 18px; }
.cd-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; font-size: 14px; }
.cd-sec-title { font-size: var(--fs-body); font-weight: 600; color: var(--head); margin-bottom: 8px; }
.si-dv { margin-top: 8px; font-size: var(--fs-aux); }
.lk-sub { margin-left: 6px; font-size: var(--fs-aux); }
.ev-group { border-top: 1px dashed var(--line); padding-top: 8px; margin-top: 8px; }
.ev-group:first-of-type { border-top: none; margin-top: 0; padding-top: 0; }
.evg-title { font-size: var(--fs-aux); font-weight: 600; margin-bottom: 6px; }
.evg-supporting { color: var(--el-color-success); }
.evg-refuting { color: var(--el-color-danger); }
.evg-undetermined { color: var(--ink-2); }
.claim { padding: 6px 0 6px 10px; border-left: 2px solid var(--line); margin-bottom: 8px; }
.claim-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.claim-title { font-size: var(--fs-body); }
.ref-list { display: flex; flex-direction: column; gap: 4px; }
.ref-row { display: flex; flex-direction: column; font-size: 12px; padding: 4px 8px; background: var(--bg); border-radius: var(--radius-ctl); }
.ref-row.unresolved { opacity: 0.65; }
.ref-meta { color: var(--ink-2); }
.ref-unresolved { color: var(--ink-2); font-style: italic; }
.ref-none, .evg-none { font-size: var(--fs-aux); padding-left: 10px; }

.fail-sample { padding: 8px 16px; }
.fs-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 4px; }
.fail-sample pre {
  margin: 0; padding: 8px 12px; background: var(--bg); border-radius: var(--radius-ctl);
  font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.fs-none { padding: 8px 16px; font-size: var(--fs-aux); color: var(--ink-2); }

.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
</style>

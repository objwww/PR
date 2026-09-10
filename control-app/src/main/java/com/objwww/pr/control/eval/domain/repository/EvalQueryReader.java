package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-5/EV-03 评测查询投影只读端口（eval_run/eval_case_result/eval_phase_event(V80)/
 * dataset_version/case_version 五表投影；零写面——V45/V80 对 control_app 只授 SELECT）。
 *
 * <p>键集分页：runs 排序 (started_at DESC, id DESC)，cursor = 上一页末行的
 * (startedAt, runId)；cases 排序 (scenario_id ASC, round_no ASC)，cursor = 上一页
 * 末行的 (scenarioId, roundNo)——明文拼接/解析归应用服务，端口只收结构化游标。
 *
 * <p>诚实纪律（RunQueryService/IncidentQueryReader 同律）：
 * <ul>
 *   <li>RUNNING/FAILED run 的聚合指标（四率 + tp/fp/fn + 计数分子分母）DB 未回填 →
 *       如实 null，不回填 0；三件套装配（numerator/denominator/status）归应用服务；</li>
 *   <li>displayName/mode（V80 列）无写面回填 → 如实 null（EV-04 命令侧落值前不编造）；</li>
 *   <li>phase/phaseEnteredAt 投影自 eval_phase_event 最新事件，无事件 → null
 *       （§5.1：阶段由 worker 落事件，后端没有的阶段数据返回 null，不猜）；</li>
 *   <li>lastProgressAt = eval_case_result 最大落档时刻（真实进展信号），无案例 → null；</li>
 *   <li>caseExecutionId = eval_case_result.id（既有稳定 uuid 主键，RV02 复合 rowKey
 *       的唯一案例执行身份）；rcaRunId/scoredReportId 为既有外键直读（可空如实 null）；</li>
 *   <li>expected/actualRootCauseJson 为 jsonb 原文（{"component","fault_type","reason_code"}
 *       三元组快照，actual 可空），摘要字符串化归应用服务。</li>
 * </ul>
 */
public interface EvalQueryReader {

    /** runs 键集游标（(started_at, id) 严格小于继续取页） */
    record KeysetCursor(Instant at, UUID id) {
    }

    /** eval_run 投影行（指标/计数列可空 = 未终态化回填；名称/模式/阶段可空 = 无真实数据源；
     *  EV-04：recoveryState/terminalReason/cancelRequestedAt/launchPlanJson 直读 V81 列与
     *  eval_run_command 受理面——无数据源如实 null） */
    record EvalRunRow(UUID runId, String datasetVersion, String registryDigest, String model,
                      String promptVersion, String configDigest, String state,
                      Instant startedAt, Instant finishedAt,
                      Double coverage, Double conditionalAccuracy, Double endToEndHitRate,
                      Double unresolvedRate, Integer tp, Integer fp, Integer fn,
                      String displayName, String mode,
                      Integer totalScenarios, Integer decidableCount, Integer hitCount,
                      Integer unresolvedCount, long caseCount, Instant lastProgressAt,
                      String phase, Instant phaseEnteredAt,
                      String recoveryState, String terminalReason,
                      Instant cancelRequestedAt, String launchPlanJson) {
    }

    /** 一页 runs；hasMore = 取到 limit+1 行（调用方据此发 nextCursor） */
    record EvalRunPage(List<EvalRunRow> items, boolean hasMore) {
    }

    /** eval_case_result 投影行（root cause/failureSample 为 jsonb 原文，摘要化归服务层） */
    record EvalCaseRow(UUID caseExecutionId, String scenarioId, int roundNo, String verdict,
                       boolean rootCauseHit, String expectedRootCauseJson,
                       String actualRootCauseJson, Long latencyMs, String failureSampleJson,
                       UUID rcaRunId, UUID scoredReportId) {
    }

    /** 一页 cases；hasMore 同 runs 惯例 */
    record EvalCasePage(List<EvalCaseRow> items, boolean hasMore) {
    }

    /** 数据集版本投影（caseCount=case_version 行数；families=去重 scenario_family_id，
     *  RLS 面下只计 control_app 可见的非 HOLDOUT 行） */
    record DatasetRow(String version, String source, long caseCount, List<String> families,
                      Instant createdAt) {
    }

    /** runs 列表页（state=null 不过滤；cursor=null 首页）。实现方内部取 limit+1 判 hasMore */
    EvalRunPage listRuns(String state, KeysetCursor cursor, int limit);

    /** run 详情行；未知 id → empty（controller 404 面） */
    Optional<EvalRunRow> findRun(UUID runId);

    /** cases 列表页（verdict=null 不过滤；afterScenario/afterRound=null 首页——
     *  (scenario_id, round_no) 严格大于继续取页） */
    EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                           Integer afterRound, int limit);

    /** 数据集版本全量（created_at DESC；数据集版本数为导入次数量级，不分页） */
    List<DatasetRow> listDatasets();
}

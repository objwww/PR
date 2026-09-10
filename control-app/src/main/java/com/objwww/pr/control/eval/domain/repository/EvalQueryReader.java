package com.objwww.pr.control.eval.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-5 评测查询投影只读端口（eval_run/eval_case_result/dataset_version/case_version
 * 四表投影；零写面——V45 对 control_app 只授 SELECT）。
 *
 * <p>键集分页：runs 排序 (started_at DESC, id DESC)，cursor = 上一页末行的
 * (startedAt, runId)；cases 排序 (scenario_id ASC, round_no ASC)，cursor = 上一页
 * 末行的 (scenarioId, roundNo)——明文拼接/解析归应用服务，端口只收结构化游标。
 *
 * <p>诚实纪律（RunQueryService/IncidentQueryReader 同律）：RUNNING/FAILED run 的
 * 聚合指标（coverage 四率 + tp/fp/fn）DB 未回填 → 如实 null，不回填 0；
 * expected/actualRootCauseJson 为 jsonb 原文（{"component","fault_type","reason_code"}
 * 三元组快照，actual 可空），摘要字符串化归应用服务。
 */
public interface EvalQueryReader {

    /** runs 键集游标（(started_at, id) 严格小于继续取页） */
    record KeysetCursor(Instant at, UUID id) {
    }

    /** eval_run 投影行（指标列可空 = 未终态化回填） */
    record EvalRunRow(UUID runId, String datasetVersion, String registryDigest, String model,
                      String promptVersion, String configDigest, String state,
                      Instant startedAt, Instant finishedAt,
                      Double coverage, Double conditionalAccuracy, Double endToEndHitRate,
                      Double unresolvedRate, Integer tp, Integer fp, Integer fn) {
    }

    /** 一页 runs；hasMore = 取到 limit+1 行（调用方据此发 nextCursor） */
    record EvalRunPage(List<EvalRunRow> items, boolean hasMore) {
    }

    /** eval_case_result 投影行（root cause/failureSample 为 jsonb 原文，摘要化归服务层） */
    record EvalCaseRow(String scenarioId, int roundNo, String verdict, boolean rootCauseHit,
                       String expectedRootCauseJson, String actualRootCauseJson,
                       Long latencyMs, String failureSampleJson) {
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

    /** run 的 eval_case_result 行数 */
    long countCases(UUID runId);

    /** cases 列表页（verdict=null 不过滤；afterScenario/afterRound=null 首页——
     *  (scenario_id, round_no) 严格大于继续取页） */
    EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                           Integer afterRound, int limit);

    /** 数据集版本全量（created_at DESC；数据集版本数为导入次数量级，不分页） */
    List<DatasetRow> listDatasets();
}

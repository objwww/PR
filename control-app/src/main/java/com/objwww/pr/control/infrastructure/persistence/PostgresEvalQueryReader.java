package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EvalQueryReader} 的 Postgres 实现（UI-5/EV-03 只读投影；JdbcClient 手写 SQL，
 * 沿 PostgresIncidentQueryReader 惯例——参数缺席即不拼条件，limit+1 判 hasMore）。
 *
 * <p>EV-03 扩展（§5.1/§5.3 第一行）全部单查询投影，不 N+1：
 * <ul>
 *   <li>caseCount/lastProgressAt = eval_case_result 相关子查询（count(*)/max(created_at)）；</li>
 *   <li>phase/phaseEnteredAt = eval_phase_event 最新事件 left join lateral
 *       （无事件 → null，如实"阶段未采集"）；</li>
 *   <li>displayName/mode/totalScenarios/decidableCount/hitCount/unresolvedCount
 *       = eval_run 直读列（V80 + V10 终态回填列，未回填如实 null）。</li>
 * </ul>
 *
 * <p>RV08 红线：用量/费用不读 PR 域模型调用账本（其 review_run_id 外键指向 PR
 * review_run，与 eval/RCA 无关）——用量分面等 R7 RCA 调用账本显式接线（EV-06），
 * 本类不出现任何按时间窗/同 UUID 的归属猜测。
 *
 * <p>EV-05 扩展（案例详情/Run 证据汇总/受限日志比较）：
 * <ul>
 *   <li>findCaseDetail 双键（runId+caseExecutionId）定位，关联链 rca_run/rca_report
 *       left join 直读，环节缺席如实 null；</li>
 *   <li>findCaseIdentity 精确键匹配（dv.version + case_key；多命中=歧义不取"最新"）；</li>
 *   <li>listCaseEvidenceRefs 单查询扁平抽取 claims 证据引用（jsonb 小字段，不上抛
 *       package 大文本），ref→rca_evidence 解析限定案例所属 rca_run 范围；</li>
 *   <li>listEvidenceMeta 以 rcaRunId+id 白名单双约束读证据元数据（跨 run 不返回）；</li>
 *   <li>listCaseLogEvidence 读 logs.query 冻结证据原文（受限日志比较的输入；
 *       不发新 Loki 查询）。</li>
 * </ul>
 *
 * <p>jsonb 列（expected/actual_root_cause、failure_sample）以 ::text 原文上抛，
 * 摘要字符串化归应用服务（纯函数可测）；avg/sum 无此面——四率是 double precision
 * 直读，caseCount 走 count(*)。
 */
public class PostgresEvalQueryReader implements EvalQueryReader {

    private static final String SELECT_RUN =
            "select r.id, r.dataset_version, r.registry_digest, r.model, r.prompt_version,"
                    + " r.config_digest, r.state, r.started_at, r.finished_at,"
                    + " r.coverage, r.conditional_accuracy, r.end_to_end_hit_rate, r.unresolved_rate,"
                    + " r.tp_count, r.fp_count, r.fn_count,"
                    + " r.display_name, r.mode,"
                    + " r.total_scenarios, r.decidable_count, r.hit_count, r.unresolved_count,"
                    + " r.recovery_state, r.terminal_reason, r.launch_plan::text as launch_plan_json,"
                    + " (select min(c.created_at) from eval_run_command c"
                    + "   where c.eval_run_id = r.id and c.command_type = 'CANCEL'"
                    + "   and c.state in ('PENDING','CLAIMED','DONE')) as cancel_requested_at,"
                    + " (select ec.gate_outcome from eval_comparison ec"
                    + "   where ec.candidate_run_id = r.id"
                    + "   order by ec.created_at desc, ec.id desc limit 1)"
                    + "   as comparison_gate_outcome,"
                    + " (select count(*) from eval_case_result c where c.eval_run_id = r.id)"
                    + "   as case_count,"
                    + " (select max(c.created_at) from eval_case_result c where c.eval_run_id = r.id)"
                    + "   as last_progress_at,"
                    + " ph.phase as phase, ph.entered_at as phase_entered_at"
                    + " from eval_run r"
                    + " left join lateral ("
                    + "   select p.phase, p.entered_at from eval_phase_event p"
                    + "   where p.eval_run_id = r.id"
                    + "   order by p.entered_at desc, p.id desc limit 1"
                    + " ) ph on true";

    /** logs.query 冻结证据类型词（= alert.application.agent.LogsAgent.EVIDENCE_TYPE，
     *  infra 不反向依赖 application，词表锚定以字面量对齐） */
    private static final String LOG_EVIDENCE_TYPE = "logs.query";

    private final JdbcClient jdbc;

    public PostgresEvalQueryReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    // ------------------------------------------------------------------ runs

    @Override
    public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = "";
        if (state != null && !state.isBlank()) {
            where = " where r.state = :state";
            params.put("state", state);
        }
        if (cursor != null) {
            where = appendAnd(where,
                    "(r.started_at, r.id) < (:cursorAt, cast(:cursorId as uuid))");
            params.put("cursorAt", Timestamp.from(cursor.at()));
            params.put("cursorId", cursor.id().toString());
        }
        params.put("lim", limit + 1);
        List<EvalRunRow> rows = new ArrayList<>(jdbc.sql(
                        SELECT_RUN + where + " order by r.started_at desc, r.id desc limit :lim")
                .params(params)
                .query(this::mapRun).list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new EvalRunPage(rows, hasMore);
    }

    @Override
    public Optional<EvalRunRow> findRun(UUID runId) {
        return jdbc.sql(SELECT_RUN + " where r.id = :id")
                .param("id", runId)
                .query(this::mapRun).optional();
    }

    // ------------------------------------------------------------------ cases

    @Override
    public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                  Integer afterRound, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = " where eval_run_id = :runId";
        params.put("runId", runId);
        if (verdict != null && !verdict.isBlank()) {
            where += " and verdict = :verdict";
            params.put("verdict", verdict);
        }
        if (afterScenario != null && afterRound != null) {
            where += " and (scenario_id, round_no) > (:afterScenario, :afterRound)";
            params.put("afterScenario", afterScenario);
            params.put("afterRound", afterRound);
        }
        params.put("lim", limit + 1);
        List<EvalCaseRow> rows = new ArrayList<>(jdbc.sql("""
                        select id as case_execution_id, scenario_id, round_no, verdict,
                               root_cause_hit,
                               expected_root_cause::text as expected_json,
                               actual_root_cause::text as actual_json,
                               latency_ms, failure_sample::text as failure_json,
                               rca_run_id, scored_report_id,
                               cause_component_hit, cause_fault_hit, cause_reason_hit,
                               checkpoints_total, checkpoints_covered,
                               checkpoint_matches::text as checkpoint_matches_json,
                               conclusion_grounded, tool_calls_total, tool_calls_unique,
                               difficulty
                        from eval_case_result
                        """ + where + " order by scenario_id asc, round_no asc limit :lim")
                .params(params)
                .query((rs, i) -> new EvalCaseRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("verdict"), rs.getBoolean("root_cause_hit"),
                        rs.getString("expected_json"), rs.getString("actual_json"),
                        rs.getObject("latency_ms", Long.class), rs.getString("failure_json"),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("scored_report_id", UUID.class),
                        (Boolean) rs.getObject("cause_component_hit"),
                        (Boolean) rs.getObject("cause_fault_hit"),
                        (Boolean) rs.getObject("cause_reason_hit"),
                        (Integer) rs.getObject("checkpoints_total"),
                        (Integer) rs.getObject("checkpoints_covered"),
                        rs.getString("checkpoint_matches_json"),
                        rs.getString("conclusion_grounded"),
                        (Integer) rs.getObject("tool_calls_total"),
                        (Integer) rs.getObject("tool_calls_unique"),
                        rs.getString("difficulty")))
                .list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new EvalCasePage(rows, hasMore);
    }

    // ------------------------------------------------------------------ datasets

    @Override
    public List<DatasetRow> listDatasets() {
        // families = 该版本 case_version 去重族键（RLS 面下仅非 HOLDOUT 可见行入桶）；
        // name/source_class/partition_class = dataset_version 头身份列（无 RLS 全可见）
        return jdbc.sql("""
                select d.id as dataset_version_id, d.name, d.version, d.source,
                       d.source_class, d.partition_class, d.created_at,
                       count(c.id) as case_count,
                       array_agg(distinct c.scenario_family_id)
                           filter (where c.id is not null) as families
                from dataset_version d
                left join case_version c on c.dataset_version_id = d.id
                group by d.id, d.name, d.version, d.source, d.source_class,
                         d.partition_class, d.created_at
                order by d.created_at desc, d.id desc
                """)
                .query((rs, i) -> {
                    List<String> families = new ArrayList<>();
                    java.sql.Array sqlArray = rs.getArray("families");
                    if (sqlArray != null) {
                        for (Object o : (Object[]) sqlArray.getArray()) {
                            families.add(String.valueOf(o));
                        }
                    }
                    java.util.Collections.sort(families);
                    return new DatasetRow(
                            rs.getObject("dataset_version_id", UUID.class),
                            rs.getString("name"), rs.getString("version"),
                            rs.getString("source"), rs.getString("source_class"),
                            rs.getString("partition_class"),
                            rs.getLong("case_count"), List.copyOf(families),
                            rs.getTimestamp("created_at").toInstant());
                }).list();
    }

    /** EV-08 HOLDOUT 计数针孔（security definer 函数；只出聚合计数三列） */
    @Override
    public List<PartitionCountRow> listPartitionCounts() {
        return jdbc.sql("""
                select dataset_version_id, partition_class, case_count
                from case_version_partition_counts()
                """)
                .query((rs, i) -> new PartitionCountRow(
                        rs.getObject("dataset_version_id", UUID.class),
                        rs.getString("partition_class"), rs.getLong("case_count")))
                .list();
    }

    // ------------------------------------------------------------------ EV-05 案例与证据

    @Override
    public Optional<EvalCaseDetailRow> findCaseDetail(UUID runId, UUID caseExecutionId) {
        return jdbc.sql("""
                        select c.id as case_execution_id, c.eval_run_id, c.scenario_id,
                               c.round_no, er.dataset_version,
                               c.selection_policy_version, c.verdict, c.root_cause_hit,
                               c.expected_root_cause::text as expected_json,
                               c.actual_root_cause::text as actual_json,
                               c.expected_symptom_codes::text as expected_symptoms_json,
                               c.actual_symptom_codes::text as actual_symptoms_json,
                               c.tp_count, c.fp_count, c.fn_count, c.latency_ms, c.silence_penalty,
                               c.failure_sample::text as failure_json, c.created_at,
                               c.rca_run_id, c.scored_attempt_id, c.scored_report_id,
                               rr.state as rca_run_state, rr.incident_id,
                               rr.started_at as rca_started_at, rr.finished_at as rca_finished_at,
                               rep.schema_version as report_schema_version,
                               rep.validation_status as report_validation_status,
                               rep.model as report_model, rep.created_at as report_created_at,
                               rep.package_json::text as package_json
                        from eval_case_result c
                        join eval_run er on er.id = c.eval_run_id
                        left join rca_run rr on rr.id = c.rca_run_id
                        left join rca_report rep on rep.id = c.scored_report_id
                        where c.eval_run_id = :runId and c.id = :caseId
                        """)
                .param("runId", runId)
                .param("caseId", caseExecutionId)
                .query((rs, i) -> new EvalCaseDetailRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getObject("eval_run_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("dataset_version"),
                        rs.getString("selection_policy_version"),
                        rs.getString("verdict"), rs.getBoolean("root_cause_hit"),
                        rs.getString("expected_json"), rs.getString("actual_json"),
                        rs.getString("expected_symptoms_json"), rs.getString("actual_symptoms_json"),
                        rs.getObject("tp_count", Integer.class),
                        rs.getObject("fp_count", Integer.class),
                        rs.getObject("fn_count", Integer.class),
                        rs.getObject("latency_ms", Long.class),
                        rs.getBoolean("silence_penalty"),
                        rs.getString("failure_json"), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("scored_attempt_id", UUID.class),
                        rs.getObject("scored_report_id", UUID.class),
                        rs.getString("rca_run_state"),
                        rs.getObject("incident_id", UUID.class),
                        ts(rs, "rca_started_at"), ts(rs, "rca_finished_at"),
                        rs.getObject("report_schema_version", Integer.class),
                        rs.getString("report_validation_status"),
                        rs.getString("report_model"), ts(rs, "report_created_at"),
                        rs.getString("package_json")))
                .optional();
    }

    @Override
    public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion, String scenarioId) {
        // 精确键匹配；version 跨 name 不唯一（uq 是 (name,version)）——多命中 = 归属歧义，
        // 不取"最新"冒充，如实 unresolved
        List<CaseIdentityRow> rows = findCaseIdentityMatches(datasetVersion, scenarioId);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    @Override
    public List<CaseIdentityRow> findCaseIdentityMatches(String datasetVersion,
                                                         String scenarioId) {
        // BA-169：透出真实匹配列表供服务层区分未解析成因（取 2 行判歧义即可）
        return jdbc.sql("""
                        select cv.case_key, cv.scenario_family_id, cv.content_digest,
                               cv.valid_from, cv.valid_to, dv.partition_class,
                               dv.name as dataset_name, dv.version as dataset_version,
                               dv.source_class
                        from case_version cv
                        join dataset_version dv on dv.id = cv.dataset_version_id
                        where dv.version = :datasetVersion and cv.case_key = :scenarioId
                        order by cv.valid_from desc, cv.id
                        limit 2
                        """)
                .param("datasetVersion", datasetVersion)
                .param("scenarioId", scenarioId)
                .query((rs, i) -> new CaseIdentityRow(
                        rs.getString("case_key"), rs.getString("scenario_family_id"),
                        rs.getString("content_digest"),
                        rs.getTimestamp("valid_from").toInstant(), ts(rs, "valid_to"),
                        rs.getString("partition_class"), rs.getString("dataset_name"),
                        rs.getString("dataset_version"), rs.getString("source_class")))
                .list();
    }

    @Override
    public List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId) {
        // 单查询扁平抽取（§5.3 纪律：不 N+1、不上抛 package 大文本——只抽 claims 的
        // status/claim_type 与 evidence_refs 字符串）。claims/evidence_refs 非数组面
        // 由 CASE 护住归零；ref→evidence 解析限定本案例 rca_run 范围（跨对象不解析）。
        return jdbc.sql("""
                        select c.id as case_execution_id, c.scenario_id, c.round_no, c.verdict,
                               c.rca_run_id, c.scored_report_id,
                               cl.claim_status, cl.claim_type, cl.ref,
                               ev.id as evidence_id, ev.evidence_type
                        from eval_case_result c
                        left join lateral (
                            select claim->>'status' as claim_status,
                                   claim->>'claim_type' as claim_type,
                                   ref.ref as ref
                            from rca_report rep,
                                 jsonb_array_elements(
                                     case when jsonb_typeof(rep.package_json->'claims') = 'array'
                                          then rep.package_json->'claims'
                                          else '[]'::jsonb end) as claim
                                 cross join lateral jsonb_array_elements_text(
                                     case when jsonb_typeof(claim->'evidence_refs') = 'array'
                                          then claim->'evidence_refs'
                                          else '[]'::jsonb end) as ref(ref)
                            where rep.id = c.scored_report_id
                        ) cl on true
                        left join rca_evidence ev
                            on c.rca_run_id is not null and ev.run_id = c.rca_run_id
                           and lower(ev.id::text) = lower(cl.ref)
                        where c.eval_run_id = :runId
                        order by c.scenario_id, c.round_no, c.id, cl.claim_type, cl.ref
                        """)
                .param("runId", runId)
                .query((rs, i) -> new CaseEvidenceRefRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("verdict"),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("scored_report_id", UUID.class),
                        rs.getString("claim_status"), rs.getString("claim_type"),
                        rs.getString("ref"),
                        rs.getObject("evidence_id", UUID.class),
                        rs.getString("evidence_type")))
                .list();
    }

    @Override
    public List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId, List<UUID> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return List.of();
        }
        // run_id + id 白名单双约束：不在本 rca_run 的 id 一律不返回（§3.4 跨对象禁读）
        return jdbc.sql("""
                        select id as evidence_id, run_id, evidence_type, source, scope,
                               time_start, time_end, payload_digest, created_at
                        from rca_evidence
                        where run_id = :rcaRunId and id in (:ids)
                        order by created_at, id
                        """)
                .param("rcaRunId", rcaRunId)
                .param("ids", evidenceIds)
                .query((rs, i) -> new EvidenceMetaRow(
                        rs.getObject("evidence_id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("evidence_type"), rs.getString("source"),
                        rs.getString("scope"),
                        ts(rs, "time_start"), ts(rs, "time_end"),
                        rs.getString("payload_digest"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId, String scenarioId) {
        // 冻结证据投影（EX-B2 logs.query 统一形状 payload 原文；时间窗/服务自证据行
        // 本身取——本读面不发新 Loki 查询，无自由查询参数）
        return jdbc.sql("""
                        select c.id as case_execution_id, c.scenario_id, c.round_no, c.rca_run_id,
                               ev.id as evidence_id, ev.source, ev.scope as scope_json,
                               ev.time_start, ev.time_end, ev.payload as payload_json,
                               ev.payload_digest, ev.created_at as evidence_created_at
                        from eval_case_result c
                        join rca_evidence ev on ev.run_id = c.rca_run_id
                            and ev.evidence_type = :logType
                        where c.eval_run_id = :runId and c.scenario_id = :scenarioId
                        order by c.round_no, ev.created_at, ev.id
                        """)
                .param("runId", runId)
                .param("scenarioId", scenarioId)
                .param("logType", LOG_EVIDENCE_TYPE)
                .query((rs, i) -> new CaseLogEvidenceRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("evidence_id", UUID.class),
                        rs.getString("source"), rs.getString("scope_json"),
                        ts(rs, "time_start"), ts(rs, "time_end"),
                        rs.getString("payload_json"), rs.getString("payload_digest"),
                        rs.getTimestamp("evidence_created_at").toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ EV-07 配对工作台

    @Override
    public Optional<CompareRunMeta> findCompareMeta(UUID runId) {
        // FUP-02：launch_plan 快照原文一并投影（冻结计划分母来源；旧跑批行 NULL 如实）
        return jdbc.sql("""
                        select id, dataset_version, registry_digest, alert_rule_digest,
                               lexicon_version, scenario_driver_version, model, prompt_version,
                               config_digest, state, launch_plan::text as launch_plan_json
                        from eval_run where id = :id
                        """)
                .param("id", runId)
                .query((rs, i) -> new CompareRunMeta(
                        rs.getObject("id", UUID.class),
                        rs.getString("dataset_version"), rs.getString("registry_digest"),
                        rs.getString("alert_rule_digest"),
                        rs.getObject("lexicon_version", Integer.class),
                        rs.getString("scenario_driver_version"), rs.getString("model"),
                        rs.getString("prompt_version"), rs.getString("config_digest"),
                        rs.getString("state"), rs.getString("launch_plan_json")))
                .optional();
    }

    /** EV-07 自动落档 baseline（终态钩子）：同 dataset_version + 同 panel 的上一个
     *  终态 run；panel 取 launch_plan 快照原文键（无快照/未填同视 null=全量原表） */
    @Override
    public Optional<UUID> findAutoCompareBaseline(UUID candidateRunId) {
        return jdbc.sql("""
                        select r.id
                        from eval_run r, eval_run c
                        where c.id = :candidateId
                          and r.id <> c.id
                          and r.dataset_version = c.dataset_version
                          and r.state in ('SUCCEEDED','FAILED')
                          and coalesce(r.launch_plan->>'panel', '')
                              = coalesce(c.launch_plan->>'panel', '')
                        order by r.finished_at desc, r.id desc
                        limit 1
                        """)
                .param("candidateId", candidateRunId)
                .query((rs, i) -> rs.getObject("id", UUID.class))
                .optional();
    }

    @Override
    public List<String> listPlanCaseKeys(String datasetVersion) {
        // FUP-02：与 listCasesForCompare 身份解析同口径（dv.version 精确键；
        // 同名版本多行/重复键去重——计划键集只做分母核算，歧义面已由身份列 null 覆盖）
        return jdbc.sql("""
                        select distinct cv.case_key
                        from case_version cv
                        join dataset_version dv on dv.id = cv.dataset_version_id
                        where dv.version = :version
                        order by cv.case_key
                        """)
                .param("version", datasetVersion)
                .query((rs, i) -> rs.getString("case_key"))
                .list();
    }

    @Override
    public List<DatasetCaseRow> listDatasetCases(String name, String version) {
        // dv(name,version) uq 精确键；payload 投影小字段（note/期望三元组/症状码），
        // rawArtifact 大文本不 SELECT；HOLDOUT RLS 不可见行天然缺席
        return jdbc.sql("""
                        select cv.case_key, cv.scenario_family_id, dv.partition_class,
                               cv.payload->'rawArtifact'->>'note' as note,
                               cv.payload->'expectedRootCause'->>'component' as exp_component,
                               cv.payload->'expectedRootCause'->>'faultType' as exp_fault_type,
                               cv.payload->'expectedRootCause'->>'reasonCode' as exp_reason_code,
                               cv.payload->'expectedSymptomCodes'::text as exp_symptoms_json
                        from case_version cv
                        join dataset_version dv on dv.id = cv.dataset_version_id
                        where dv.name = :name and dv.version = :version
                        order by cv.case_key
                        """)
                .param("name", name)
                .param("version", version)
                .query((rs, i) -> new DatasetCaseRow(
                        rs.getString("case_key"), rs.getString("scenario_family_id"),
                        rs.getString("partition_class"), rs.getString("note"),
                        rs.getString("exp_component"), rs.getString("exp_fault_type"),
                        rs.getString("exp_reason_code"), rs.getString("exp_symptoms_json")))
                .list();
    }

    @Override
    public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
        // 身份列精确键横向解析（dv.version = run.dataset_version 且 case_key = scenario_id；
        // 多命中=歧义/零命中=无匹配或 HOLDOUT RLS 不可见 → null，与 EV-05 同律不取"最新"）
        return jdbc.sql("""
                        select c.id as case_execution_id, c.scenario_id, c.round_no, c.verdict,
                               c.root_cause_hit, c.expected_root_cause::text as expected_json,
                               c.selection_policy_version,
                               idn.content_digest, idn.scenario_family_id,
                               c.rca_run_id, c.latency_ms
                        from eval_case_result c
                        join eval_run er on er.id = c.eval_run_id
                        left join lateral (
                            select case when count(*) = 1 then min(cv.content_digest) end
                                       as content_digest,
                                   case when count(*) = 1 then min(cv.scenario_family_id) end
                                       as scenario_family_id
                            from case_version cv
                            join dataset_version dv on dv.id = cv.dataset_version_id
                            where dv.version = er.dataset_version
                              and cv.case_key = c.scenario_id
                        ) idn on true
                        where c.eval_run_id = :runId
                        order by c.scenario_id asc, c.round_no asc
                        limit :lim
                        """)
                .param("runId", runId)
                .param("lim", limit)
                .query((rs, i) -> new CompareCaseRow(
                        rs.getObject("case_execution_id", UUID.class),
                        rs.getString("scenario_id"), rs.getInt("round_no"),
                        rs.getString("verdict"), rs.getBoolean("root_cause_hit"),
                        rs.getString("expected_json"), rs.getString("selection_policy_version"),
                        rs.getString("content_digest"), rs.getString("scenario_family_id"),
                        rs.getObject("rca_run_id", UUID.class),
                        rs.getObject("latency_ms", Long.class)))
                .list();
    }

    /** R6/EV-06：eval run 关联的已结算模型调用行（rca_run_id 身份链 join，不碰 PR 域账本） */
    @Override
    public List<UsageCallRow> listUsageCalls(UUID evalRunId) {
        return jdbc.sql("""
                        select c.eval_run_id, c.rca_run_id, m.attempt_id, m.role_id, m.state,
                               (m.usage->>'prompt_tokens')::int as prompt_tokens,
                               (m.usage->>'completion_tokens')::int as completion_tokens,
                               (m.usage->>'total_tokens')::int as total_tokens,
                               m.cost_micros, m.pricing_version, m.currency, m.usage_missing
                        from eval_case_result c
                        join rca_model_call m on m.run_id = c.rca_run_id
                        where c.eval_run_id = :runId
                          and c.rca_run_id is not null
                          and m.state <> 'PENDING'
                        order by c.rca_run_id, m.attempt_id, m.action_seq, m.physical_seq
                        """)
                .param("runId", evalRunId)
                .query(EvalQueryReaderUsageRows::map)
                .list();
    }

    /** 批量面（列表接线，单查询禁 N+1）；空集直返不拼 IN () */
    @Override
    public List<UsageCallRow> listUsageCallsForRuns(Iterable<UUID> evalRunIds) {
        List<UUID> ids = new ArrayList<>();
        for (UUID id : evalRunIds) {
            ids.add(Objects.requireNonNull(id));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select c.eval_run_id, c.rca_run_id, m.attempt_id, m.role_id, m.state,
                               (m.usage->>'prompt_tokens')::int as prompt_tokens,
                               (m.usage->>'completion_tokens')::int as completion_tokens,
                               (m.usage->>'total_tokens')::int as total_tokens,
                               m.cost_micros, m.pricing_version, m.currency, m.usage_missing
                        from eval_case_result c
                        join rca_model_call m on m.run_id = c.rca_run_id
                        where c.eval_run_id in (:ids)
                          and c.rca_run_id is not null
                          and m.state <> 'PENDING'
                        order by c.eval_run_id, c.rca_run_id, m.attempt_id, m.action_seq, m.physical_seq
                        """)
                .param("ids", ids)
                .query(EvalQueryReaderUsageRows::map)
                .list();
    }

    /** BA-176：失败账批量面（版本解读数据面，单查询禁 N+1）；空集直返不拼 IN () */
    @Override
    public List<ModelCallFailureRow> listModelCallFailuresForRuns(Iterable<UUID> evalRunIds) {
        List<UUID> ids = new ArrayList<>();
        for (UUID id : evalRunIds) {
            ids.add(Objects.requireNonNull(id));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select c.eval_run_id, coalesce(m.error_code, 'UNKNOWN') as error_code,
                               count(*) as cnt
                          from eval_case_result c
                          join rca_model_call m on m.run_id = c.rca_run_id
                         where c.eval_run_id in (:ids)
                           and c.rca_run_id is not null
                           and m.state = 'FAILED'
                         group by c.eval_run_id, coalesce(m.error_code, 'UNKNOWN')
                         order by c.eval_run_id, cnt desc, error_code
                        """)
                .param("ids", ids)
                .query((rs, i) -> new ModelCallFailureRow(
                        rs.getObject("eval_run_id", UUID.class),
                        rs.getString("error_code"), rs.getLong("cnt")))
                .list();
    }

    /** EV-09：场景轮次聚合（actual_root_cause 以 jsonb 原文文本计 distinct，null 记一值） */
    @Override
    public List<ScenarioRoundStatRow> listScenarioRoundStatsForRuns(Iterable<UUID> evalRunIds) {
        List<UUID> ids = new ArrayList<>();
        for (UUID id : evalRunIds) {
            ids.add(Objects.requireNonNull(id));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select eval_run_id, scenario_id,
                               count(*) as rounds,
                               count(*) filter (where verdict = 'DECIDABLE' and root_cause_hit) as hits,
                               count(distinct verdict) as distinct_verdicts,
                               count(distinct coalesce(actual_root_cause::text, '~null~'))
                                   as distinct_actuals
                          from eval_case_result
                         where eval_run_id in (:ids)
                         group by eval_run_id, scenario_id
                        """)
                .param("ids", ids)
                .query((rs, i) -> new ScenarioRoundStatRow(
                        rs.getObject("eval_run_id", UUID.class),
                        rs.getString("scenario_id"),
                        rs.getLong("rounds"),
                        rs.getLong("hits"),
                        rs.getInt("distinct_verdicts"),
                        rs.getInt("distinct_actuals")))
                .list();
    }

    /** RUNNING 期实时聚合：已结清案例的命中/判定/症状计数单行直出 */
    @Override
    public LiveMetricRow liveMetrics(UUID runId) {
        return jdbc.sql("""
                        select count(*) as settled,
                               count(*) filter (where verdict = 'DECIDABLE') as decidable,
                               count(*) filter (where verdict = 'DECIDABLE' and root_cause_hit) as hits,
                               count(*) filter (where verdict = 'UNRESOLVED') as unresolved,
                               coalesce(sum(tp_count), 0) as tp,
                               coalesce(sum(fp_count), 0) as fp,
                               coalesce(sum(fn_count), 0) as fn
                          from eval_case_result
                         where eval_run_id = :id
                        """)
                .param("id", runId)
                .query((rs, i) -> new LiveMetricRow(
                        rs.getLong("settled"), rs.getLong("decidable"), rs.getLong("hits"),
                        rs.getLong("unresolved"),
                        rs.getLong("tp"), rs.getLong("fp"), rs.getLong("fn")))
                .single();
    }

    /** 每案 token 聚合（cases 列表批量面，禁 N+1）。口径：scored_attempt_id 在场
     *  即按被评分 attempt 聚合（多 attempt run 只计被评那一次的调用消耗），缺席则
     *  整 rca_run 聚合；只计已结算行；token 列全 null → 该列和 null 如实（不猜零） */
    @Override
    public List<CaseTokenRow> listCaseTokenTotals(UUID evalRunId, List<UUID> caseExecutionIds) {
        if (caseExecutionIds == null || caseExecutionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select c.id as case_execution_id,
                               sum((m.usage->>'prompt_tokens')::int)::bigint as prompt_tokens,
                               sum((m.usage->>'completion_tokens')::int)::bigint as completion_tokens,
                               sum((m.usage->>'total_tokens')::int)::bigint as total_tokens
                        from eval_case_result c
                        join rca_model_call m on m.run_id = c.rca_run_id
                            and (c.scored_attempt_id is null
                                 or m.attempt_id = c.scored_attempt_id)
                            and m.state <> 'PENDING'
                        where c.eval_run_id = :runId and c.id in (:ids)
                        group by c.id
                        """)
                .param("runId", evalRunId)
                .param("ids", caseExecutionIds)
                .query((rs, i) -> new CaseTokenRow(
                        rs.getObject("case_execution_id", UUID.class),
                        (Long) rs.getObject("prompt_tokens"),
                        (Long) rs.getObject("completion_tokens"),
                        (Long) rs.getObject("total_tokens")))
                .list();
    }

    // ------------------------------------------------------------------ P4 安全裁决投影

    /** run 全部安全裁决行（P4；红队归属=案例键解析到 REDTEAM 分区；禁 N+1）。
     *  join 顺序必须先限定数据集版本再挂案例——v1/v2 数据集共用 case_key 时
     *  （V142 退役 V141 种子），反向 join 会行倍增（195 实证 assessed 6=3×2） */
    @Override
    public List<CaseSafetyRow> listCaseSafety(UUID evalRunId) {
        return jdbc.sql("""
                        select s.scenario_id, s.round_no, s.verdict,
                               s.violations::text as violations_json,
                               s.redteam,
                               ec.root_cause_hit
                          from eval_case_safety s
                          left join eval_case_result ec
                                 on ec.eval_run_id = s.eval_run_id
                                and ec.scenario_id = s.scenario_id
                                and ec.round_no = s.round_no
                         where s.eval_run_id = :runId
                         order by s.scenario_id asc, s.round_no asc
                        """)
                .param("runId", evalRunId)
                .query((rs, i) -> new CaseSafetyRow(
                        rs.getString("scenario_id"),
                        rs.getInt("round_no"),
                        rs.getString("verdict"),
                        rs.getString("violations_json"),
                        rs.getBoolean("redteam"),
                        (Boolean) rs.getObject("root_cause_hit")))
                .list();
    }

    // ------------------------------------------------------------------ P7 judge 裁决投影

    /** run 全部 judge 裁决行（P6-G7；单查询禁 N+1；judge 未启用 → 空表如实缺席） */
    @Override
    public List<CaseJudgeRow> listJudge(UUID evalRunId) {
        return jdbc.sql("""
                        select scenario_id, round_no, rubric_version, model,
                               answers::text as answers_json,
                               passed, total, verdict, error
                          from eval_case_judge
                         where eval_run_id = :runId
                         order by scenario_id asc, round_no asc
                        """)
                .param("runId", evalRunId)
                .query((rs, i) -> new CaseJudgeRow(
                        rs.getString("scenario_id"),
                        rs.getInt("round_no"),
                        rs.getString("rubric_version"),
                        rs.getString("model"),
                        rs.getString("answers_json"),
                        (Integer) rs.getObject("passed"),
                        (Integer) rs.getObject("total"),
                        rs.getString("verdict"),
                        rs.getString("error")))
                .list();
    }

    // ------------------------------------------------------------------ M-d T5 六要素投影

    /** run 全部六要素检出行（V152 eval_case_six_parts；单查询禁 N+1；
     *  无检出 → 空表如实缺席） */
    @Override
    public List<SixPartsRow> listSixParts(UUID evalRunId) {
        return jdbc.sql("""
                        select scenario_id, round_no, complete, confidence_level
                          from eval_case_six_parts
                         where eval_run_id = :runId
                         order by scenario_id asc, round_no asc
                        """)
                .param("runId", evalRunId)
                .query((rs, i) -> new SixPartsRow(
                        rs.getString("scenario_id"),
                        rs.getInt("round_no"),
                        rs.getBoolean("complete"),
                        rs.getString("confidence_level")))
                .list();
    }

    // ------------------------------------------------------------------ M-d T3 过程面聚合

    /** run 级过程面单查询现算（M-d T3）：eval_case_result V140 过程列 + 延迟分位 +
     *  工具账本关联子查询（rca_tool_invocation 经 rca_run_id 集合，V15 授权面）；
     *  无已结清案例 → 全 0/null 如实 */
    @Override
    public ProcessMetricsRow processMetrics(UUID runId) {
        return jdbc.sql("""
                        select count(*)                                                     as settled,
                               count(*) filter (where verdict = 'STRUCTURE_REJECTED')       as structure_rejected,
                               coalesce(sum(tool_calls_total), 0)                           as tool_calls_total,
                               coalesce(sum(tool_calls_unique), 0)                          as tool_calls_unique,
                               coalesce(sum(checkpoints_total), 0)                          as checkpoints_total,
                               coalesce(sum(checkpoints_covered), 0)                        as checkpoints_covered,
                               count(conclusion_grounded)                                   as grounded_assessed,
                               count(*) filter (where conclusion_grounded = 'GROUNDED')     as grounded,
                               percentile_cont(0.5) within group (order by latency_ms)
                                   filter (where latency_ms is not null)                    as p50_latency_ms,
                               percentile_cont(0.95) within group (order by latency_ms)
                                   filter (where latency_ms is not null)                    as p95_latency_ms,
                               (select count(*) from rca_tool_invocation ti
                                 where ti.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))     as tool_call_total,
                               (select count(*) from rca_tool_invocation ti
                                 where ti.state = 'FAILED'
                                   and ti.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))     as tool_call_failed
                          from eval_case_result
                         where eval_run_id = :runId
                        """)
                .param("runId", runId)
                .query((rs, i) -> new ProcessMetricsRow(
                        rs.getLong("settled"),
                        rs.getLong("structure_rejected"),
                        rs.getLong("tool_calls_total"),
                        rs.getLong("tool_calls_unique"),
                        rs.getLong("checkpoints_total"),
                        rs.getLong("checkpoints_covered"),
                        rs.getLong("grounded_assessed"),
                        rs.getLong("grounded"),
                        (Long) rs.getObject("p50_latency_ms", Long.class) != null
                                ? rs.getLong("p50_latency_ms") : null,
                        (Long) rs.getObject("p95_latency_ms", Long.class) != null
                                ? rs.getLong("p95_latency_ms") : null,
                        rs.getLong("tool_call_total"),
                        rs.getLong("tool_call_failed")))
                .list().stream().findFirst()
                .orElse(new ProcessMetricsRow(0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0));
    }

    // ------------------------------------------------------------------ M-d T8 审批链观测

    /** 审批五表存在性计数（V114/V119 链路：intent→request→decisions/grant→authorization；
     *  run 集合=本 eval run 的 rca_run_id 集） */
    @Override
    public ApprovalChainRow approvalChain(UUID runId) {
        return jdbc.sql("""
                        select (select count(*) from action_intent i
                                  where i.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))          as intents,
                               (select count(*) from approval_request r
                                  join action_intent i on i.intent_id = r.intent_id
                                  where i.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))          as requests,
                               (select count(*) from approval_decisions d
                                  join approval_request r on r.request_id = d.request_id
                                  join action_intent i on i.intent_id = r.intent_id
                                  where i.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))          as decisions,
                               (select count(*) from approval_grant g
                                  join approval_request r on r.request_id = g.request_id
                                  join action_intent i on i.intent_id = r.intent_id
                                  where i.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))          as grants,
                               (select count(*) from operation_authorization o
                                  join approval_grant g on g.grant_id = o.grant_id
                                  join approval_request r on r.request_id = g.request_id
                                  join action_intent i on i.intent_id = r.intent_id
                                  where i.run_id in (select e2.rca_run_id from eval_case_result e2
                                                      where e2.eval_run_id = :runId
                                                        and e2.rca_run_id is not null))          as authorizations
                        """)
                .param("runId", runId)
                .query((rs, i) -> new ApprovalChainRow(
                        rs.getLong("intents"),
                        rs.getLong("requests"),
                        rs.getLong("decisions"),
                        rs.getLong("grants"),
                        rs.getLong("authorizations")))
                .list().stream().findFirst()
                .orElse(new ApprovalChainRow(0, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------ A3 阶段事件读面

    /** eval_phase_event 键集分页（无 seq 列——(entered_at, id) 严格大于续页，升序；
     *  detail jsonb ::text 原文上抛；RV08：不读 PR 域账本，也不读 rca_model_call） */
    @Override
    public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = " where eval_run_id = :runId";
        params.put("runId", runId);
        if (cursor != null) {
            where += " and (entered_at, id) > (:cursorAt, cast(:cursorId as uuid))";
            params.put("cursorAt", Timestamp.from(cursor.at()));
            params.put("cursorId", cursor.id().toString());
        }
        params.put("lim", limit + 1);
        List<EvalPhaseEventRow> rows = new ArrayList<>(jdbc.sql("""
                        select id, phase, entered_at, worker_id, detail::text as detail
                        from eval_phase_event
                        """ + where + " order by entered_at asc, id asc limit :lim")
                .params(params)
                .query((rs, i) -> new EvalPhaseEventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("phase"),
                        rs.getTimestamp("entered_at").toInstant(),
                        rs.getString("worker_id"),
                        rs.getString("detail")))
                .list());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        return new EvalPhaseEventPage(rows, hasMore);
    }

    /** usage 行映射（单 run / 批量两查询共用同一列面） */
    private static final class EvalQueryReaderUsageRows {
        private EvalQueryReaderUsageRows() {
        }

        static UsageCallRow map(ResultSet rs, int rowNum) throws SQLException {
            return new UsageCallRow(
                    rs.getObject("eval_run_id", UUID.class),
                    rs.getObject("rca_run_id", UUID.class),
                    rs.getObject("attempt_id", UUID.class),
                    rs.getString("role_id"), rs.getString("state"),
                    (Integer) rs.getObject("prompt_tokens"),
                    (Integer) rs.getObject("completion_tokens"),
                    (Integer) rs.getObject("total_tokens"),
                    (Long) rs.getObject("cost_micros"),
                    rs.getString("pricing_version"), rs.getString("currency"),
                    rs.getBoolean("usage_missing"));
        }
    }

    // ------------------------------------------------------------------ 内部

    private EvalRunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new EvalRunRow(
                rs.getObject("id", UUID.class),
                rs.getString("dataset_version"),
                rs.getString("registry_digest"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getString("config_digest"),
                rs.getString("state"),
                rs.getTimestamp("started_at").toInstant(),
                ts(rs, "finished_at"),
                rs.getObject("coverage", Double.class),
                rs.getObject("conditional_accuracy", Double.class),
                rs.getObject("end_to_end_hit_rate", Double.class),
                rs.getObject("unresolved_rate", Double.class),
                rs.getObject("tp_count", Integer.class),
                rs.getObject("fp_count", Integer.class),
                rs.getObject("fn_count", Integer.class),
                rs.getString("display_name"),
                rs.getString("mode"),
                rs.getObject("total_scenarios", Integer.class),
                rs.getObject("decidable_count", Integer.class),
                rs.getObject("hit_count", Integer.class),
                rs.getObject("unresolved_count", Integer.class),
                rs.getLong("case_count"),
                ts(rs, "last_progress_at"),
                rs.getString("phase"),
                ts(rs, "phase_entered_at"),
                rs.getString("recovery_state"),
                rs.getString("terminal_reason"),
                ts(rs, "cancel_requested_at"),
                rs.getString("launch_plan_json"),
                rs.getString("comparison_gate_outcome"));
    }

    private static String appendAnd(String where, String clause) {
        return where.isEmpty() ? " where " + clause : where + " and " + clause;
    }

    private static Instant ts(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}

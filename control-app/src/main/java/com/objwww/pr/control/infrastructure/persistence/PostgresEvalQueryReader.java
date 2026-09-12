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
                               rca_run_id, scored_report_id
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
                        rs.getObject("scored_report_id", UUID.class)))
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
        // 不取"最新"冒充，如实 unresolved（取 2 行判歧义即可）
        List<CaseIdentityRow> rows = jdbc.sql("""
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
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
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
        return jdbc.sql("""
                        select id, dataset_version, registry_digest, alert_rule_digest,
                               lexicon_version, scenario_driver_version, model, prompt_version,
                               config_digest, state
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
                        rs.getString("state")))
                .optional();
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

    // ------------------------------------------------------------------ A3 阶段事件读面

    /** eval_phase_event 键集分页（无 seq 列——(entered_at, id) 严格大于续页，升序；
     *  detail jsonb ::text 原文上抛，不读 model_call_ledger/rca_model_call——RV08） */
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

package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.model.EvalComparisonRecord;
import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link EvalComparisonRepository} 的 Postgres 实现（EV-07/V85；JdbcClient 手写 SQL，
 * 沿 PostgresEvalRunCommandRepository 惯例）。insert-only：本类无 UPDATE/DELETE 语句；
 * "生效面" = 同 (baseline,candidate) 对最新落档（created_at DESC, id DESC 稳定落点）。
 *
 * <p>授权面（V85）：control_app select,insert；eval_app 与生产角色显式 revoke。
 * jsonb 列（dimension_diffs/stats_snapshot/gate_reasons）以 ::text 原文出入，
 * 结构装配归应用服务。
 */
public class PostgresEvalComparisonRepository implements EvalComparisonRepository {

    private final JdbcClient jdbc;

    public PostgresEvalComparisonRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insert(EvalComparisonRecord record) {
        jdbc.sql("""
                        insert into eval_comparison (
                            id, baseline_run_id, candidate_run_id, comparable,
                            dimension_diffs, paired_count, unpaired_count,
                            improved_count, regressed_count, flat_count,
                            stats_snapshot, gate_outcome, gate_reasons, gate_rule_version,
                            actor, created_at
                        ) values (
                            :id, :baseline, :candidate, :comparable,
                            cast(:dimensionDiffs as jsonb), :paired, :unpaired,
                            :improved, :regressed, :flat,
                            cast(:statsSnapshot as jsonb), :gateOutcome,
                            cast(:gateReasons as jsonb), :gateRuleVersion,
                            :actor, :createdAt
                        )
                        """)
                .param("id", record.id())
                .param("baseline", record.baselineRunId())
                .param("candidate", record.candidateRunId())
                .param("comparable", record.comparable())
                .param("dimensionDiffs", record.dimensionDiffsJson())
                .param("paired", record.pairedCount())
                .param("unpaired", record.unpairedCount())
                .param("improved", record.improvedCount())
                .param("regressed", record.regressedCount())
                .param("flat", record.flatCount())
                .param("statsSnapshot", record.statsSnapshotJson())
                .param("gateOutcome", record.gateOutcome())
                .param("gateReasons", toJsonArray(record.gateReasons()))
                .param("gateRuleVersion", record.gateRuleVersion())
                .param("actor", record.actor())
                .param("createdAt", Timestamp.from(record.createdAt()))
                .update();
    }

    @Override
    public Optional<EvalComparisonRecord> findLatestByPair(UUID baselineRunId,
                                                           UUID candidateRunId) {
        return jdbc.sql("""
                        select id, baseline_run_id, candidate_run_id, comparable,
                               dimension_diffs::text as dimension_diffs_json,
                               paired_count, unpaired_count, improved_count, regressed_count,
                               flat_count, stats_snapshot::text as stats_snapshot_json,
                               gate_outcome, gate_reasons::text as gate_reasons_json,
                               gate_rule_version, actor, created_at
                        from eval_comparison
                        where baseline_run_id = :baseline and candidate_run_id = :candidate
                        order by created_at desc, id desc
                        limit 1
                        """)
                .param("baseline", baselineRunId)
                .param("candidate", candidateRunId)
                .query((rs, i) -> new EvalComparisonRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("baseline_run_id", UUID.class),
                        rs.getObject("candidate_run_id", UUID.class),
                        rs.getBoolean("comparable"),
                        rs.getString("dimension_diffs_json"),
                        rs.getInt("paired_count"), rs.getInt("unpaired_count"),
                        rs.getInt("improved_count"), rs.getInt("regressed_count"),
                        rs.getInt("flat_count"),
                        rs.getString("stats_snapshot_json"),
                        rs.getString("gate_outcome"),
                        fromJsonArray(rs.getString("gate_reasons_json")),
                        rs.getString("gate_rule_version"),
                        rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** 机器码原因表 → jsonb 数组原文（元素为固定机器码词，无引号注入面） */
    private static String toJsonArray(List<String> reasons) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(reasons.get(i)).append('"');
        }
        return out.append(']').toString();
    }

    /** jsonb 数组原文 → 机器码原因表（本表写面只出 toJsonArray 形态，按逗号切分即可） */
    private static List<String> fromJsonArray(String json) {
        if (json == null || json.length() < 2) {
            return List.of();
        }
        String inner = json.substring(1, json.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(inner.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .toList();
    }
}

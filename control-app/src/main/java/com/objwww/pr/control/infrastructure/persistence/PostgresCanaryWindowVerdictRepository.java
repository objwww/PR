package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * canary_window_verdict 的 Postgres 实现（V30；M6-01）。append-only：只 append 与
 * findByRollout，零 UPDATE/DELETE 路径（授权面同构封死）；uq_cwv_window 冲突 =
 * 同窗幂等（返回 false，非错误）。jsonb 载荷 Map 序列化在仓储边界完成
 * （release 域零框架）。
 */
public class PostgresCanaryWindowVerdictRepository implements CanaryWindowVerdictRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APPEND_SQL = """
            INSERT INTO canary_window_verdict (
                rollout_id, candidate_digest, rollout_policy_digest, capability_digest,
                from_percent, to_percent, window_seq, window_start, window_end,
                evidence_class, eligible_incidents, raw_counts, strata_json,
                control_json, absolute_slo_json, critical_pass, scored_json,
                verdict, evidence_refs, evaluated_at
            ) VALUES (
                :rolloutId, :candidateDigest, :rolloutPolicyDigest, :capabilityDigest,
                :fromPercent, :toPercent, :windowSeq, :windowStart, :windowEnd,
                :evidenceClass, :eligibleIncidents, CAST(:rawCounts AS jsonb),
                CAST(:strata AS jsonb), CAST(:control AS jsonb),
                CAST(:absoluteSlo AS jsonb), :criticalPass, CAST(:scored AS jsonb),
                :verdict, CAST(:evidenceRefs AS jsonb), :evaluatedAt
            )
            ON CONFLICT ON CONSTRAINT uq_cwv_window DO NOTHING
            """;

    private static final String FIND_SQL = """
            SELECT rollout_id, candidate_digest, rollout_policy_digest, capability_digest,
                   from_percent, to_percent, window_seq, window_start, window_end,
                   evidence_class, eligible_incidents, raw_counts, strata_json,
                   control_json, absolute_slo_json, critical_pass, scored_json,
                   verdict, evidence_refs, evaluated_at
              FROM canary_window_verdict
             WHERE rollout_id = :rolloutId AND candidate_digest = :candidateDigest
             ORDER BY window_seq
            """;

    private final JdbcClient jdbc;

    public PostgresCanaryWindowVerdictRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public boolean append(VerdictRow row) {
        try {
            int inserted = jdbc.sql(APPEND_SQL)
                    .param("rolloutId", row.rolloutId())
                    .param("candidateDigest", row.candidateDigest())
                    .param("rolloutPolicyDigest", row.rolloutPolicyDigest())
                    .param("capabilityDigest", row.capabilityDigest())
                    .param("fromPercent", row.fromPercent())
                    .param("toPercent", row.toPercent())
                    .param("windowSeq", row.windowSeq())
                    .param("windowStart", Timestamp.from(row.windowStart()))
                    .param("windowEnd", row.windowEnd() == null
                            ? null : Timestamp.from(row.windowEnd()))
                    .param("evidenceClass", row.evidenceClass())
                    .param("eligibleIncidents", row.eligibleIncidents())
                    .param("rawCounts", writeJson(row.rawCounts()))
                    .param("strata", writeJson(row.strata()))
                    .param("control", writeJson(row.control()))
                    .param("absoluteSlo", writeJson(row.absoluteSlo()))
                    .param("criticalPass", row.criticalPass(), Types.BOOLEAN)
                    .param("scored", row.scored() == null ? null : writeJson(row.scored()))
                    .param("verdict", row.verdict())
                    .param("evidenceRefs", writeJson(
                            row.evidenceRefs() == null ? List.of() : row.evidenceRefs()))
                    .param("evaluatedAt", Timestamp.from(row.evaluatedAt()))
                    .update();
            return inserted == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public List<VerdictRow> findByRollout(UUID rolloutId, String candidateDigest) {
        return jdbc.sql(FIND_SQL)
                .param("rolloutId", rolloutId)
                .param("candidateDigest", candidateDigest)
                .query((rs, i) -> new VerdictRow(
                        rs.getObject("rollout_id", UUID.class),
                        rs.getString("candidate_digest"),
                        rs.getString("rollout_policy_digest"),
                        rs.getString("capability_digest"),
                        rs.getInt("from_percent"),
                        rs.getInt("to_percent"),
                        rs.getInt("window_seq"),
                        rs.getTimestamp("window_start").toInstant(),
                        rs.getTimestamp("window_end") == null
                                ? null : rs.getTimestamp("window_end").toInstant(),
                        rs.getString("evidence_class"),
                        rs.getInt("eligible_incidents"),
                        readMap(rs.getString("raw_counts")),
                        readMap(rs.getString("strata_json")),
                        readMap(rs.getString("control_json")),
                        readMap(rs.getString("absolute_slo_json")),
                        (Boolean) rs.getObject("critical_pass"),
                        readMap(rs.getString("scored_json")),
                        rs.getString("verdict"),
                        readRefs(rs.getString("evidence_refs")),
                        rs.getTimestamp("evaluated_at").toInstant()))
                .list();
    }

    // ------------------------------------------------------------------ 内部

    private static String writeJson(Object payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canary 窗判定 jsonb 序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String content) {
        if (content == null) {
            return null;
        }
        try {
            return JSON.readValue(content,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canary 窗判定 jsonb 解析失败", e);
        }
    }

    private static List<String> readRefs(String content) {
        if (content == null) {
            return List.of();
        }
        try {
            return JSON.readValue(content, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canary 窗判定 evidence_refs 解析失败", e);
        }
    }
}

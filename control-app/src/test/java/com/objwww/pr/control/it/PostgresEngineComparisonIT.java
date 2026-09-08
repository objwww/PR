package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresEngineComparisonRepository;
import com.objwww.pr.control.release.domain.repository.EngineComparisonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-02 engine_comparison 真 PG 集成（V32；命名 *IT 本机无 docker 自动跳过，
 * 195 真跑补证据）：
 * <ul>
 *   <li>append 落行（control_app 角色）+ uq_ec_pair 幂等（同对照重放 false 不重记）；</li>
 *   <li>jsonb 往返：holmes/native outcome、六维 disagree_flags、cost_compare 读回
 *       形状保持（SimplePropertyRowMapper 不支持 Map.class 的 BA-41 面由仓储行映射承担）；</li>
 *   <li>noise_baseline 可空（M6-05 底噪校准前恒 null）；</li>
 *   <li>insert-only 授权面真库兜底：control_app 可 insert/select，UPDATE/DELETE
 *       被 revoke 拒绝（42501 → DataAccessException）。</li>
 * </ul>
 */
class PostgresEngineComparisonIT extends PostgresITBase {

    private PostgresEngineComparisonRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PostgresEngineComparisonRepository(
                JdbcClient.create(controlDataSource()));
    }

    @Test
    void appendIsIdempotentAndJsonbRoundTripPreservesShape() {
        UUID nativeRunId = UUID.randomUUID();
        Map<String, Object> holmes = new LinkedHashMap<>();
        holmes.put("engine", "HOLMES");
        holmes.put("run_id", UUID.randomUUID().toString());
        holmes.put("validation_status", "STRUCTURE_VALIDATED");
        holmes.put("total_tokens", 1234);
        holmes.put("latency_ms", 8000L);
        Map<String, Object> nativeSide = new LinkedHashMap<>();
        nativeSide.put("engine", "NATIVE");
        nativeSide.put("run_id", nativeRunId.toString());
        nativeSide.put("latency_ms", 8000L);
        Map<String, Object> resultFlag = new LinkedHashMap<>();
        resultFlag.put("dim", "result");
        resultFlag.put("holmes", Map.of("root_cause", Map.of("component", "checkout")));
        resultFlag.put("native", Map.of("root_cause", Map.of("component", "unknown")));
        Map<String, Object> cost = Map.of("holmes_total_tokens", 1234);

        EngineComparisonRepository.ComparisonRow row = new EngineComparisonRepository
                .ComparisonRow(nativeRunId, "k".repeat(64), "am4-shadow-trigger",
                "a".repeat(64), holmes, nativeSide, List.of(resultFlag), null, cost);

        assertThat(repo.append(row)).isTrue();
        // 同对照重放 = uq_ec_pair 幂等，不重记
        assertThat(repo.append(row)).isFalse();
        assertThat(count("engine_comparison")).isEqualTo(1);

        List<EngineComparisonRepository.ComparisonRow> rows =
                repo.findByNativeRunId(nativeRunId);
        assertThat(rows).hasSize(1);
        EngineComparisonRepository.ComparisonRow back = rows.get(0);
        assertThat(back.nativeRunId()).isEqualTo(nativeRunId);
        assertThat(back.comparisonKey()).hasSize(64);
        assertThat(back.shadowExecRef()).isEqualTo("am4-shadow-trigger");
        assertThat(back.snapshotDigest()).isEqualTo("a".repeat(64));
        assertThat(back.holmesOutcome())
                .containsEntry("engine", "HOLMES")
                .containsEntry("validation_status", "STRUCTURE_VALIDATED");
        assertThat(((Number) back.holmesOutcome().get("total_tokens")).intValue())
                .isEqualTo(1234);
        assertThat(back.nativeOutcome()).containsEntry("engine", "NATIVE");
        assertThat(back.disagreeFlags()).hasSize(1);
        assertThat(back.disagreeFlags().get(0)).containsEntry("dim", "result");
        // noise_baseline M6-05 前恒 null（列可空，读回保 null）
        assertThat(back.noiseBaseline()).isNull();
        assertThat(((Number) back.costCompare().get("holmes_total_tokens")).intValue())
                .isEqualTo(1234);
    }

    @Test
    void controlAppCannotUpdateOrDeleteAppendOnlyEvidence() {
        UUID nativeRunId = UUID.randomUUID();
        assertThat(repo.append(new EngineComparisonRepository.ComparisonRow(nativeRunId,
                "b".repeat(64), "am4-shadow-trigger", "c".repeat(64),
                Map.of("engine", "HOLMES"), Map.of("engine", "NATIVE"),
                List.of(), null, Map.of()))).isTrue();

        // 授权纪律（V32 revoke）：control_app 无 UPDATE/DELETE——观察面结论不可改写
        assertThatThrownBy(() -> controlJdbc.sql(
                        "UPDATE engine_comparison SET shadow_exec_ref = 'tampered'"
                                + " WHERE native_run_id = :id")
                .param("id", nativeRunId).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> controlJdbc.sql(
                        "DELETE FROM engine_comparison WHERE native_run_id = :id")
                .param("id", nativeRunId).update())
                .isInstanceOf(DataAccessException.class);
        assertThat(count("engine_comparison")).isEqualTo(1);
    }
}

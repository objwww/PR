package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-A0 验收（L1，真 PG）：调查输入身份三列契约（V36；docs/告警-EXA0-身份与执行契约.md §5/§7）。
 *
 * <p>钉四件事：
 * <ul>
 *   <li>铸点 3 参 insertRouted：调查输入身份 digest + 冻结时间窗（F14，锚=铸造时刻）
 *       随行一次落列，findRoutingById 三值分列读回——config_digest（V25 配置身份）≠
 *       investigation_input_digest（F04 输入身份），语义分离在列级可证；</li>
 *   <li>存量行兼容面：V25 形态铸造（2 参）与裸 insert 的行身份三列全空——执行器
 *       回退语义（NativeInvestigationExecutor §3.2）的数据级事实；</li>
 *   <li>V36 迁移契约：列类型/约束逐项（information_schema）；</li>
 *   <li>ck_rca_run_window 行为面：半开窗/倒挂窗被拒，双列有序放行；
 *       ck_rca_tool_invocation_action_seq：负值被拒，null/非负放行。</li>
 * </ul>
 *
 * <p>语义级红绿（输入身份 vs 快照身份混用的表征翻转）在 L0：NativeRcaAgentTest
 * （exa0-red2.log / exa0-green-agent.log）；本类是真 PG 上的落库/读回/约束验收。
 */
class ExA0InvestigationIdentityIT extends PostgresITBase {

    private static final Instant MINTED_AT = Instant.ofEpochSecond(1_800_000_000L);

    private RcaRunRepository runs;
    private PostgresIncidentRepository incidents;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
    }

    // ------------------------------------------------ 铸点冻结：三值分列 + 窗口读回

    @Test
    void mintFreezesInvestigationIdentityAndWindowAsDistinctColumns() {
        Incident incident = insertIncident("exa0-mint");
        RcaRun run = run(incident.id());
        Digest config = Digest.sha256Of("bundle-v1");
        InvestigationInputs inputs = InvestigationInputs.freezeAt(incident, MINTED_AT);

        runs.insertRouted(run, new RcaRunRouting(
                RcaEngine.NATIVE, config, incident.incidentKey(), 37,
                "BUCKETED_NATIVE"), inputs);

        RcaRunRepository.RoutingView view = runs.findRoutingById(run.id()).orElseThrow();
        // 三 digest 分列（F04）：配置身份 ≠ 输入身份（快照身份在 rca_evidence_snapshot，
        // V16 已有列——三者不共列不共值）
        assertThat(view.engine()).isEqualTo(RcaEngine.NATIVE);
        assertThat(view.configDigest()).isEqualTo(config.hex());
        assertThat(view.investigationInputDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(config.hex());
        assertThat(view.investigationInputDigest())
                .isEqualTo(inputs.inputDigest().hex());
        // 冻结时间窗（F14）：锚=铸造时刻，非执行时刻
        assertThat(view.windowStart()).isEqualTo(MINTED_AT.minusSeconds(600));
        assertThat(view.windowEnd()).isEqualTo(MINTED_AT);

        // 列面：char(64) 落行值与读回一致（SimplePropertyRowMapper 不支持 Map——显式行映射）
        Map<String, Object> row = adminJdbc.sql("""
                SELECT investigation_input_digest, window_start, window_end
                  FROM rca_run WHERE id = :id
                """).param("id", run.id())
                .query((rs, i) -> Map.<String, Object>of(
                        "digest", rs.getString("investigation_input_digest"),
                        "start", rs.getTimestamp("window_start").toInstant(),
                        "end", rs.getTimestamp("window_end").toInstant()))
                .single();
        assertThat(row.get("digest")).isEqualTo(inputs.inputDigest().hex());
        assertThat(row.get("start")).isEqualTo(MINTED_AT.minusSeconds(600));
        assertThat(row.get("end")).isEqualTo(MINTED_AT);
    }

    // --------------------------------------- 存量行兼容面：身份三列可空 = 回退触发条件

    @Test
    void preV36RowsCarryNullIdentityForExecutorFallback() {
        UUID incidentId = insertIncident("exa0-legacy-routed").id();
        // V25 形态铸造（迁移缝隙/存量路径）：路由四列在场、身份三列全空
        RcaRun routed = run(incidentId);
        runs.insertRouted(routed, new RcaRunRouting(
                RcaEngine.NATIVE, Digest.sha256Of("bundle-v1"),
                "alertname=higherror|service=checkout", 37, "BUCKETED_NATIVE"));

        RcaRunRepository.RoutingView view = runs.findRoutingById(routed.id()).orElseThrow();
        assertThat(view.configDigest()).isNotNull();
        assertThat(view.investigationInputDigest()).isNull();
        assertThat(view.windowStart()).isNull();
        assertThat(view.windowEnd()).isNull();

        // 更早形态（裸 insert）：engine 走 V25 默认 HOLMES，身份三列同样全空
        UUID bareIncident = insertIncident("exa0-legacy-bare").id();
        RcaRun bare = run(bareIncident);
        runs.insert(bare);
        RcaRunRepository.RoutingView bareView = runs.findRoutingById(bare.id()).orElseThrow();
        assertThat(bareView.engine()).isEqualTo(RcaEngine.HOLMES);
        assertThat(bareView.investigationInputDigest()).isNull();
        assertThat(bareView.windowStart()).isNull();
        assertThat(bareView.windowEnd()).isNull();
    }

    // --------------------------------------------------------- V36 迁移契约钉（列级）

    @Test
    void v36ContractPinsColumnTypesAndCheckConstraints() {
        Map<String, String> columns = adminJdbc.sql("""
                SELECT column_name, data_type, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_name = 'rca_run'
                   AND column_name IN ('investigation_input_digest','window_start','window_end')
                """).query((rs, i) -> Map.entry(rs.getString("column_name"),
                rs.getString("data_type")
                        + (rs.getObject("character_maximum_length") == null
                        ? "" : "(" + rs.getInt("character_maximum_length") + ")")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(columns)
                .containsEntry("investigation_input_digest", "character(64)")
                .containsEntry("window_start", "timestamp with time zone")
                .containsEntry("window_end", "timestamp with time zone");

        String actionSeqType = adminJdbc.sql("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_name = 'rca_tool_invocation' AND column_name = 'action_seq'
                """).query(String.class).single();
        assertThat(actionSeqType).isEqualTo("bigint");

        List<String> checks = adminJdbc.sql("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid IN ('rca_run'::regclass, 'rca_tool_invocation'::regclass)
                   AND contype = 'c'
                   AND conname IN ('ck_rca_run_window', 'ck_rca_tool_invocation_action_seq')
                """).query(String.class).list();
        assertThat(checks).containsExactlyInAnyOrder(
                "ck_rca_run_window", "ck_rca_tool_invocation_action_seq");
    }

    // --------------------------------------------------------- ck_rca_run_window 行为面

    @Test
    void windowCheckRejectsHalfWindowAndInversion() {
        RcaRun run = run(insertIncident("exa0-window").id());
        runs.insert(run);

        // 只落起点（半开窗）被拒
        assertThat(chainContains(() -> controlJdbc.sql("""
                        UPDATE rca_run SET window_start = now() WHERE id = :id
                        """).param("id", run.id()).update(),
                "ck_rca_run_window")).isTrue();
        // 倒挂窗被拒
        assertThat(chainContains(() -> controlJdbc.sql("""
                        UPDATE rca_run SET window_start = now() + interval '10s',
                            window_end = now() WHERE id = :id
                        """).param("id", run.id()).update(),
                "ck_rca_run_window")).isTrue();
        // 双列有序放行（F14 冻结窗合法形态）
        controlJdbc.sql("""
                UPDATE rca_run SET window_start = :s, window_end = :e WHERE id = :id
                """).param("s", java.sql.Timestamp.from(MINTED_AT.minusSeconds(600)))
                .param("e", java.sql.Timestamp.from(MINTED_AT))
                .param("id", run.id()).update();
        RcaRunRepository.RoutingView view = runs.findRoutingById(run.id()).orElseThrow();
        assertThat(view.windowStart()).isEqualTo(MINTED_AT.minusSeconds(600));
        assertThat(view.windowEnd()).isEqualTo(MINTED_AT);
    }

    // ------------------------------------------- action_seq 契约列行为面（EX-A4a 接线前钉）

    @Test
    void actionSeqCheckRejectsNegativeAndAllowsNull() {
        RcaRun run = run(insertIncident("exa0-action-seq").id());
        runs.insert(run);
        UUID invocationId = UUID.randomUUID();

        // null 放行（EX-A4a F16 接线前的迁移缝隙形态）；query(Long.class)/single() 都拒 null 行，
        // 可空列读走 optional + 显式映射
        controlJdbc.sql("""
                INSERT INTO rca_tool_invocation(id, run_id, task_id, attempt_id, call_seq,
                    tool_name, tool_version, action_digest, state)
                VALUES (:id, :run, :task, :attempt, 1, 'prometheus', 'v1', :digest, 'PENDING')
                """).param("id", invocationId).param("run", run.id())
                .param("task", UUID.randomUUID()).param("attempt", UUID.randomUUID())
                .param("digest", Digest.sha256Of("action").value()).update();
        Optional<Long> actionSeq = adminJdbc.sql("""
                        SELECT action_seq FROM rca_tool_invocation WHERE id = :id
                        """).param("id", invocationId)
                .query((rs, n) -> rs.getObject("action_seq") == null
                        ? null : rs.getLong("action_seq"))
                .optional();
        assertThat(actionSeq).as("action_seq 可空列读回 null").isEmpty();

        // 负值被拒（逻辑动作序非负）
        assertThat(chainContains(() -> controlJdbc.sql("""
                        UPDATE rca_tool_invocation SET action_seq = -1 WHERE id = :id
                        """).param("id", invocationId).update(),
                "ck_rca_tool_invocation_action_seq")).isTrue();

        // 非负放行
        controlJdbc.sql("UPDATE rca_tool_invocation SET action_seq = 3 WHERE id = :id")
                .param("id", invocationId).update();
        assertThat(adminJdbc.sql("SELECT action_seq FROM rca_tool_invocation WHERE id = :id")
                .param("id", invocationId).query(Long.class).single()).isEqualTo(3L);
    }

    // ------------------------------------------------------------------ 辅助

    /** 断言辅助：执行应抛异常，且整条 cause 链文本包含预期片段（AlertV9MigrationContractIT 同形） */
    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }

    private Incident insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Incident incident = new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now);
        incidents.insert(incident);
        return incident;
    }

    private RcaRun run(UUID incidentId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new RcaRun(id, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED,
                Digest.sha256Of("run-" + id), now.minus(Duration.ofMinutes(4)), now,
                null, null, null);
    }
}

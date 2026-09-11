package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallFenceException;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.identity.InvestigationInputs;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresOperatorCommandRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaModelCallLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunConfigEpochRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-04 验收（L1，真 PG，195 窗真值；本机无 docker 自动跳过）：配置代际史与
 * epoch 栅栏的数据级事实（V63 rca_run_config_epoch + V48 rca_model_call 栅栏面）。
 *
 * <p>钉五件事：
 * <ul>
 *   <li>准入播种：insertRouted 携带 configDigest → epoch=0 行（appliedBy=ADMISSION、
 *       sourceCommandId 空）同铸点落行，digest 与路由一致（§185 初始 bundle 不可改写的
 *       史锚）；</li>
 *   <li>追加史幂等/唯一面：同 (run_id, config_epoch) 重复 append=false（§207 UNIQUE 的
 *       ON CONFLICT 语义）；findCurrent=最大 epoch；</li>
 *   <li>并发双追加仅一胜（H05 数据级）：两线程争同 epoch，恰一 true；</li>
 *   <li>epoch 栅栏（H04 真值）：切换生效（append epoch1）后，携带旧 epoch 的 open =
 *       RcaModelCallFenceException 零行落账；新 epoch open 放行；</li>
 *   <li>命令面 CAS：advanceState from-guard 单向推进；findWaitingOverdue 只捞
 *       WAITING_SAFE_POINT 且 deadline 已过的 CONFIG_SWITCH 行；代际史
 *       append-only 授权面（control_app 无 UPDATE/DELETE）。</li>
 * </ul>
 *
 * <p>语义级红绿（切换编排/拒绝矩阵/兼容校验）在 L0：RunConfigSwitchServiceTest；
 * 本类是上述行为在真 PG 上的落库/约束/授权验收。
 */
class En04ConfigEpochIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    private PostgresRcaRunRepository runs;
    private RunConfigEpochRepository epochs;
    private PostgresIncidentRepository incidents;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        epochs = new PostgresRunConfigEpochRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc, epochs);
        incidents = new PostgresIncidentRepository(jdbc);
    }

    // ------------------------------------------------ 准入播种：epoch0 随铸点落行

    @Test
    void insertRoutedSeedsAdmissionEpochZero() {
        RcaRun run = mintRoutedRun("en04-admission");
        UUID runId = run.id();
        String configHex = Digest.sha256Of("en04-admission-bundle").hex();

        RunConfigEpochRepository.EpochRow current =
                epochs.findCurrent(runId).orElseThrow();
        assertThat(current.configEpoch()).isZero();
        assertThat(current.releaseDigest()).isEqualTo(configHex);
        assertThat(current.appliedBy()).isEqualTo("ADMISSION");
        assertThat(current.sourceCommandId()).isNull();
        assertThat(epochs.history(runId)).hasSize(1);

        // §207 UNIQUE(run_id, config_epoch)：重复 append = 幂等无行（false），史仍一行
        assertThat(epochs.append(runId, 0L, configHex, null, "ADMISSION", null)).isFalse();
        assertThat(epochs.history(runId)).hasSize(1);
    }

    // --------------------------------------- 追加史 + 并发双追加仅一胜（H05 数据级）

    @Test
    void appendRaceOnSameEpochExactlyOneWinner() throws Exception {
        UUID runId = mintRoutedRun("en04-race").id();

        assertThat(epochs.append(runId, 1L, "b".repeat(64),
                UUID.randomUUID(), "op-a", "热更新")).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> contender = () -> epochs.append(runId, 2L,
                    "c".repeat(64), UUID.randomUUID(), "op-b", "热更新");
            List<Future<Boolean>> both = List.of(pool.submit(contender),
                    pool.submit(contender));
            boolean first = both.get(0).get(30, TimeUnit.SECONDS);
            boolean second = both.get(1).get(30, TimeUnit.SECONDS);
            assertThat(first ^ second).as("同 epoch 并发双追加恰一 true").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(epochs.findCurrent(runId).orElseThrow().configEpoch()).isEqualTo(2L);
        assertThat(epochs.history(runId))
                .extracting(RunConfigEpochRepository.EpochRow::configEpoch)
                .containsExactly(0L, 1L, 2L);

        // append-only 授权面：control_app 只许 INSERT，无 UPDATE/DELETE（V63 grant/revoke）
        assertThat(chainContains(() -> controlJdbc.sql(
                        "UPDATE rca_run_config_epoch SET release_digest = :d WHERE run_id = :r")
                .param("d", "f".repeat(64)).param("r", runId).update(),
                "permission denied")).isTrue();
        assertThat(chainContains(() -> controlJdbc.sql(
                        "DELETE FROM rca_run_config_epoch WHERE run_id = :r")
                .param("r", runId).update(),
                "permission denied")).isTrue();
    }

    // ------------------------------------------- epoch 栅栏真值：旧代际零触网（H04）

    @Test
    void ledgerOpenFencesStaleEpochAfterSwitch() {
        UUID runId = mintRoutedRun("en04-fence").id();
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        PostgresRcaModelCallLedger ledger = new PostgresRcaModelCallLedger(
                jdbc, new ObjectMapper(), controlTx);

        // 切换前：epoch0 动作正常领取发送资格
        UUID freshCall = UUID.randomUUID();
        ledger.open(call(freshCall, runId, 0L));
        assertThat(ledger.findUnsettledByRun(runId)).hasSize(1);

        // 切换生效（append epoch1）：旧 epoch0 新动作 = EPOCH_FENCE，零行落账
        assertThat(epochs.append(runId, 1L, "b".repeat(64),
                UUID.randomUUID(), "op-fence", "热更新")).isTrue();
        assertThatThrownBy(() -> ledger.open(call(UUID.randomUUID(), runId, 0L)))
                .isInstanceOf(RcaModelCallFenceException.class);
        assertThat(ledger.findUnsettledByRun(runId)).hasSize(1);

        // 新 epoch 动作放行（新调用绑新 epoch，§231）
        UUID newEpochCall = UUID.randomUUID();
        ledger.open(call(newEpochCall, runId, 1L));
        assertThat(ledger.findUnsettledByRun(runId))
                .extracting(RcaModelCallLedger.UnsettledRow::id)
                .containsExactly(freshCall, newEpochCall);
    }

    // --------------------------------------------- 命令面 CAS + 过期巡回（PG 面）

    @Test
    void commandCasFromGuardAndOverdueScan() {
        UUID runId = mintRoutedRun("en04-command").id();
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        PostgresOperatorCommandRepository commands =
                new PostgresOperatorCommandRepository(jdbc);

        UUID waitingId = UUID.randomUUID();
        commands.insert(new OperatorCommand(waitingId, runId,
                OperatorCommand.Type.CONFIG_SWITCH, "op-cas", 0,
                Map.of("deadline", NOW.plusSeconds(600).toString()),
                OperatorCommand.State.PERSISTED, "op-it", NOW, null));
        UUID overdueId = UUID.randomUUID();
        commands.insert(new OperatorCommand(overdueId, runId,
                OperatorCommand.Type.CONFIG_SWITCH, "op-overdue", 0,
                Map.of("deadline", NOW.minusSeconds(60).toString()),
                OperatorCommand.State.PERSISTED, "op-it", NOW, null));

        // from-guard CAS：from 不符不动行；from 符合单向推进
        assertThat(commands.advanceState(waitingId,
                OperatorCommand.State.WAITING_SAFE_POINT,
                OperatorCommand.State.APPLIED, NOW)).isFalse();
        assertThat(commands.advanceState(waitingId,
                OperatorCommand.State.PERSISTED,
                OperatorCommand.State.WAITING_SAFE_POINT, null)).isTrue();
        assertThat(commands.advanceState(waitingId,
                OperatorCommand.State.PERSISTED,
                OperatorCommand.State.APPLIED, NOW)).isFalse();
        assertThat(commands.advanceState(waitingId,
                OperatorCommand.State.WAITING_SAFE_POINT,
                OperatorCommand.State.APPLIED, NOW)).isTrue();

        // H14 巡回面：只捞 WAITING 且 deadline 已过的行（APPLIED 行与未来 deadline 不入列）
        assertThat(commands.findWaitingOverdue(NOW))
                .extracting(OperatorCommand::id)
                .containsExactly(overdueId);
    }

    // ------------------------------------------------------------------ 辅助

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

    /** 铸一条 NATIVE run（3 参 insertRouted：路由 + 调查输入身份）——准入播种随之发生。 */
    private RcaRun mintRoutedRun(String tag) {
        Incident incident = insertIncident(tag);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        RcaRun run = new RcaRun(id, incident.id(), 0, RunTrigger.INITIAL,
                RcaRunState.QUEUED, Digest.sha256Of("run-" + tag),
                now.minus(Duration.ofMinutes(4)), now, null, null, null);
        runs.insertRouted(run, new RcaRunRouting(RcaEngine.NATIVE,
                Digest.sha256Of(tag + "-bundle"), incident.incidentKey(), 37,
                "IT_EN04"), InvestigationInputs.freezeAt(incident, now));
        return run;
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

    private static RcaModelCallLedger.OpenRow call(UUID id, UUID runId, Long configEpoch) {
        return new RcaModelCallLedger.OpenRow(id, runId, UUID.randomUUID(),
                UUID.randomUUID(), 1L, 0, 0, "primary", "1",
                Digest.sha256Of("role").hex(), Digest.sha256Of("prompt").hex(),
                null, null, configEpoch,
                configEpoch == null ? null
                        : (configEpoch == 0L ? Digest.sha256Of("en04-fence-bundle").hex()
                        : "b".repeat(64)),
                0);
    }
}

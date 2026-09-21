package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.EvalRunMetadata;
import com.objwww.pr.control.eval.domain.GoldenScenarioRegistry;
import com.objwww.pr.control.eval.domain.model.EvalRunCommand;
import com.objwww.pr.control.eval.domain.repository.EvalPhaseEventSink;
import com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-190 缝排查定谳测试（2026-09-19 批件 e6c06cea 实证：终态 FAILED 证明新代码在跑，
 * 但激活请求撞空 tag 形态 chaos-eval-s3-r1）——走 EvalLaunchExecutor 全路径
 * （launch 命令 → runner 派生 → driver 拷贝 → chaos-admin 激活请求体），装配与
 * EvalRunnerConfig 同构：真实 {@link ArenaChaosScenarioDriver}（env 静态 tag 空串）
 * + 传输面假件。断言：configured tag 空时，到达 chaos-admin 的 scenarioId 必须是
 * 按 evalRunId 派生的形态，空 tag 形态不得出现在激活/解除请求里。
 */
class EvalLaunchRunTagPropagationTest {

    private static final String REGISTRY = """
            registry_version: 1
            schema_version: 1
            lexicon_binding: "lex"
            scenarios:
              - scenario_id: S3
                name: duplicateOrder
                driver: ArenaChaosScenarioDriver
                chaos_family: F1
                target: order-arena
                expected_root_cause:
                  component: order-arena
                  fault_type: IDEMPOTENCY
                  reason_code: X
                expected_symptom_codes:
                  - ArenaOrderDuplicate
                timing:
                  preheat_seconds: 1
                  hold_seconds: 1
                  max_firing_wait_seconds: 1
                  max_resolved_wait_seconds: 1
                  cleanup_timeout_seconds: 1
            """;

    // ------------------------------------------------------------------ 假件

    private static final class FakeChaosAdminClient implements ChaosAdminClient {
        Map<String, Object> lastOnBody;
        final List<Map<String, Object>> offBodies = new ArrayList<>();

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            lastOnBody = body;
            return new Activation("sess-fixed",
                    String.valueOf(body.get("scenarioId")), 7L, "fp-fixed");
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            offBodies.add(body);
            return true;
        }

        @Override
        public SessionStatus status(String scenarioId) {
            return new SessionStatus("CLOSED", 7L);
        }
    }

    private static final class FakeAlertProbe implements AlertProbe {
        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rule=" + alertname);
        }
    }

    private static final class FakeTraffic implements ArenaTrafficClient {
        int orders;

        @Override
        public String createOrder(String intentId, String correlationId, String sku) {
            return "order-" + (++orders);
        }

        @Override
        public void payOrder(String orderId, String correlationId) {
        }
    }

    private static EvalRunCommandRepository stubCommands() {
        return new EvalRunCommandRepository() {
            @Override public void insert(EvalRunCommand c) { }
            @Override public Optional<EvalRunCommand> findByKey(EvalRunCommand.Type t,
                    String k) {
                return Optional.empty();
            }
            @Override public Optional<EvalRunCommand> findLatestLaunch(UUID runId) {
                return Optional.empty();
            }
            @Override public boolean cancelAccepted(UUID runId) {
                return false;
            }
            @Override public Optional<Instant> cancelRequestedAt(UUID runId) {
                return Optional.empty();
            }
            @Override public Optional<EvalRunCommand> claimNextLaunch(String w, Instant at) {
                return Optional.empty();
            }
            @Override public boolean finish(UUID id, EvalRunCommand.State s, Instant at) {
                return true;
            }
            @Override public List<EvalRunCommand> findOrphanedClaims(Instant before) {
                return List.of();
            }
            @Override public boolean requeue(UUID id) {
                return false;
            }
        };
    }

    private static EvalRunRepository stubEvalRuns() {
        return new EvalRunRepository() {
            @Override public void insertRunning(com.objwww.pr.control.eval.domain.EvalRun r) { }
            @Override public boolean finalizeOnce(com.objwww.pr.control.eval.domain.EvalRun r) {
                return true;
            }
            @Override public boolean applyLaunchIdentity(UUID id, String d, String m, String p) {
                return true;
            }
            @Override public boolean updateRecoveryState(UUID id, String s) {
                return true;
            }
            @Override public boolean insertCaseResult(
                    com.objwww.pr.control.eval.domain.EvalCaseResult r) {
                return true;
            }
            @Override public Optional<com.objwww.pr.control.eval.domain.EvalRun> findById(
                    UUID id) {
                return Optional.empty();
            }
            @Override public List<com.objwww.pr.control.eval.domain.EvalRun> findStrandedRuns(
                    Instant startedBefore) {
                return List.of();
            }
            @Override public List<com.objwww.pr.control.eval.domain.EvalCaseResult>
                    findCasesByRunId(UUID id) {
                return List.of();
            }
        };
    }

    // ------------------------------------------------------------------ 装配

    /** 与 EvalRunnerConfig 同构装配：真实 Arena 驱动（env 静态 tag = configuredEnvTag） */
    private static EvalLaunchExecutor executor(FakeChaosAdminClient client,
                                               String configuredEnvTag,
                                               String configuredRunTag) {
        FakeAlertProbe probe = new FakeAlertProbe();
        ArenaChaosScenarioDriver arena = new ArenaChaosScenarioDriver(client, probe,
                new FakeTraffic(), "eval-ds-1", configuredEnvTag, 0);
        com.objwww.pr.control.alert.support.AlertInMemoryStores.Runs runs =
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Runs();
        SingleCaseScorer scorer = new SingleCaseScorer(runs,
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Reports(),
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.Investigations(),
                new com.objwww.pr.control.alert.support.AlertInMemoryStores.ToolCalls(),
                new ScenarioEvaluator(
                        com.objwww.pr.control.eval.domain.SynonymLexicon.load("""
                                lexicon_version: 1
                                components:
                                  - code: order-arena
                                    synonyms: []
                                fault_types:
                                  - code: IDEMPOTENCY
                                    synonyms: []
                                reason_codes:
                                  - code: X
                                    fault_type: IDEMPOTENCY
                                    synonyms: []
                                """)));
        EvalRunMetadata metadata = new EvalRunMetadata(1, "eval-ds-1",
                Digest.sha256Of("registry"), 1, "m", "p", Digest.sha256Of("prompt"),
                Digest.sha256Of("tools"), null, null, null, null, null, "fp",
                Digest.sha256Of("rules"), "drv-1", "grader-1");
        EvalPhaseEventSink sink = (runId, phase, at, workerId, detail) -> { };
        EvalBatchRunner.EvalClock clock = new EvalBatchRunner.EvalClock() {
            @Override public Instant now() {
                return Instant.parse("2026-09-19T00:00:00Z");
            }

            @Override public void sleepSeconds(long s) { }
        };
        return new EvalLaunchExecutor(GoldenScenarioRegistry.load(REGISTRY),
                Map.of("ArenaChaosScenarioDriver", arena), probe,
                (alertname, maxWaitSeconds) -> true,
                (golden, roundNo, activatedAt, timeoutSeconds) -> Optional.empty(),
                scorer, stubEvalRuns(), new BaselineReportGenerator(), sink, stubCommands(),
                metadata, 1, clock, "worker-tag-test",
                EvalLaunchGate.closed(Set.of("L"), "eval-ds-1", 1, 10), null,
                configuredRunTag);
    }

    private static EvalRunCommand launchCommand(UUID evalRunId) {
        String payload = "{\"displayName\":\"n\",\"mode\":\"L\","
                + "\"datasetVersion\":\"eval-ds-1\",\"model\":null,\"promptVersion\":null,"
                + "\"budgetMaxTokens\":null,\"maxConcurrency\":null,"
                + "\"deadlineSeconds\":null,\"roundsPerScenario\":null}";
        return EvalRunCommand.pending(UUID.randomUUID(), EvalRunCommand.Type.LAUNCH,
                evalRunId, "key-tag", payload, Digest.sha256Of(payload).value(),
                "operator", Instant.parse("2026-09-19T00:00:00Z"));
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("BA-190 复现面：空 configured tag 批件，激活/解除请求必须带派生 tag 形态 scenario id")
    void emptyConfiguredTag_activationCarriesDerivedTag() {
        UUID evalRunId = UUID.fromString("e6c06cea-702a-4cd0-9f4a-90b606680cc4");
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        EvalLaunchExecutor executor = executor(client, "", "");

        executor.execute(launchCommand(evalRunId));

        String derivedTag = EvalRunTags.effective("", evalRunId);
        String expectedSid = "chaos-eval-" + derivedTag + "-s3-r1";
        assertThat(client.lastOnBody).as("激活请求必须到达 chaos-admin").isNotNull();
        assertThat(client.lastOnBody.get("scenarioId"))
                .as("空 tag 不得退化为 chaos-eval-s3-r1（2026-09-19 撞 uq_chaos_scenario 形态）")
                .isEqualTo(expectedSid);
        assertThat(client.offBodies).hasSize(1);
        assertThat(client.offBodies.getFirst().get("scenarioId")).isEqualTo(expectedSid);
    }

    @Test
    @DisplayName("对照面：configured tag 非空原样生效（旧语义零漂移）")
    void configuredTag_passesThroughUnchanged() {
        UUID evalRunId = UUID.randomUUID();
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        EvalLaunchExecutor executor = executor(client, "T99", "T99");

        executor.execute(launchCommand(evalRunId));

        assertThat(client.lastOnBody.get("scenarioId")).isEqualTo("chaos-eval-t99-s3-r1");
        assertThat(client.offBodies.getFirst().get("scenarioId"))
                .isEqualTo("chaos-eval-t99-s3-r1");
    }
}

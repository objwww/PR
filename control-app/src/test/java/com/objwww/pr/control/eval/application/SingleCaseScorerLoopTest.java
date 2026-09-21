package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.model.LoopTraceInput;
import com.objwww.pr.control.eval.domain.repository.EvalCaseLoopSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ME-T12a（D05）SingleCaseScorer.recordLoop 终态收尾装配：只读观测投影
 * （rca_tool_invocation + rca_model_call 归并 LoopEvent 流）→ LoopTraceEvaluator
 * 纯函数 → V162 落行；投影读失败落 ERROR 行；落库失败不回滚评分主链；
 * sink 缺席 = 不落。生产投影无环境真值（loopCase=false + onset=null 观测子集）。
 */
class SingleCaseScorerLoopTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Investigations investigations =
            new AlertInMemoryStores.Investigations();
    private final AlertInMemoryStores.ToolCalls toolCalls =
            new AlertInMemoryStores.ToolCalls();

    private GoldenCase golden;

    @BeforeEach
    void setUp() {
        golden = new GoldenCase("S1", "paymentFailure=50%", "FlagdScenarioDriver", null,
                "payment", EXPECTED, List.of("checkout"),
                new GoldenCase.Timing(1, 1, 1, 1, 1));
    }

    /** 捕获面：内存 sink + 可故障投影读缝行 */
    private static final class Capturing {
        final List<List<Object>> rows = new ArrayList<>();
        List<SingleCaseScorer.LoopInvocationRow> invocations = List.of();
        List<SingleCaseScorer.LoopModelCallRow> modelCalls = List.of();
        boolean failReads;
        boolean failInsert;

        final EvalCaseLoopSink sink = new EvalCaseLoopSink() {
            @Override
            public void insert(UUID caseResultId, UUID evalRunId, String scenarioId,
                               int roundNo, String graderVersion, String stopReason,
                               Integer detectionEventIndex,
                               Integer firstNoProgressEventIndex, int postStopNewActions,
                               long physicalCallsFromOnset, Long tokensFromOnset,
                               Long secondsFromOnset, String checksJson,
                               String metricsJson, String failureLabelsJson) {
                if (failInsert) {
                    throw new IllegalStateException("insert down");
                }
                rows.add(List.of(caseResultId, evalRunId, scenarioId, roundNo,
                        graderVersion, String.valueOf(stopReason),
                        String.valueOf(detectionEventIndex),
                        String.valueOf(firstNoProgressEventIndex), postStopNewActions,
                        physicalCallsFromOnset, String.valueOf(tokensFromOnset),
                        String.valueOf(secondsFromOnset), checksJson, metricsJson,
                        failureLabelsJson));
            }
        };
    }

    /** 假 jdbc：子类覆写两条只读投影缝返回假行（13 形构造器，loopSink 在末尾） */
    private SingleCaseScorer scorer(Capturing cap) {
        return new SingleCaseScorer(runs, reports, investigations, toolCalls,
                new ScenarioEvaluator(SynonymLexicon.load("""
                        lexicon_version: 1
                        components:
                          - code: payment
                            synonyms: [payment-svc]
                        fault_types:
                          - code: BUSINESS_ERROR_RATE
                            synonyms: [业务错误率升高]
                        reason_codes:
                          - code: PAYMENT_CHARGE_FAILURE
                            fault_type: BUSINESS_ERROR_RATE
                            synonyms: [扣款失败]
                        """)),
                null, null, null, null, null, null, null, cap.sink) {
            @Override
            List<LoopInvocationRow> loopInvocations(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.invocations;
            }

            @Override
            List<LoopModelCallRow> loopModelCalls(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.modelCalls;
            }
        };
    }

    private static SingleCaseScorer.LoopInvocationRow invocation(String id, long callSeq,
                                                                 String digest,
                                                                 String state,
                                                                 String reasonCode,
                                                                 String contentDigest,
                                                                 Instant startedAt) {
        return new SingleCaseScorer.LoopInvocationRow(UUID.fromString(id),
                UUID.randomUUID(), "logs.query", callSeq, digest, state, reasonCode,
                contentDigest, startedAt);
    }

    private static SingleCaseScorer.LoopModelCallRow modelCall(String id, long actionSeq,
                                                               String usageJson,
                                                               Instant createdAt) {
        return new SingleCaseScorer.LoopModelCallRow(UUID.fromString(id),
                UUID.randomUUID(), actionSeq, 1, "SUCCESS", null, usageJson, createdAt);
    }

    private Capturing seededProjection() {
        Capturing cap = new Capturing();
        // call_seq 与时间序故意相反（投影按发生时刻归并，不按 call_seq）
        cap.invocations = List.of(
                invocation("00000000-0000-0000-0000-0000000000b1", 2, "S1", "SUCCESS",
                        null, "pd-1", T0),
                invocation("00000000-0000-0000-0000-0000000000b2", 1, "S1", "FAILED",
                        "TIMEOUT", null, T0.plusSeconds(60)));
        cap.modelCalls = List.of(
                modelCall("00000000-0000-0000-0000-0000000000a1", 5,
                        "{\"prompt_tokens\":10,\"completion_tokens\":5,"
                                + "\"total_tokens\":15}", T0.plusSeconds(30)));
        return cap;
    }

    @Test
    @DisplayName("投影归并：TOOL_CALL/MODEL_ROUND 按发生时刻定序，重复 digest 标 reusedEvidence，token 取 total_tokens")
    void assembleLoopInputMergesInvocationAndModelCallStream() {
        Capturing cap = seededProjection();
        UUID rcaRunId = UUID.randomUUID();

        LoopTraceInput in = scorer(cap).assembleLoopInput(rcaRunId);

        assertThat(in.caseId()).isEqualTo(rcaRunId.toString());
        assertThat(in.loopCase()).isFalse();
        assertThat(in.loopOnsetEventIndex()).isNull();
        assertThat(in.events()).hasSize(3);
        LoopTraceInput.LoopEvent e0 = in.events().get(0);
        assertThat(e0.kind()).isEqualTo(LoopTraceInput.Kind.TOOL_CALL);
        assertThat(e0.toolName()).isEqualTo("logs.query");
        assertThat(e0.actionDigest()).isEqualTo("S1");
        assertThat(e0.physical()).isTrue();
        assertThat(e0.reusedEvidence()).isFalse();
        assertThat(e0.success()).isTrue();
        assertThat(e0.contentDigest()).isEqualTo("pd-1");
        assertThat(e0.tokenCost()).isNull();
        LoopTraceInput.LoopEvent e1 = in.events().get(1);
        assertThat(e1.kind()).isEqualTo(LoopTraceInput.Kind.MODEL_ROUND);
        assertThat(e1.tokenCost()).isEqualTo(15L);
        LoopTraceInput.LoopEvent e2 = in.events().get(2);
        assertThat(e2.kind()).isEqualTo(LoopTraceInput.Kind.TOOL_CALL);
        assertThat(e2.reusedEvidence()).as("同 digest 本 run 重复 → 复用推导").isTrue();
        assertThat(e2.success()).isFalse();
        assertThat(e2.failureReason()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("终态死循环落档：观测子集出数——真值依赖检查 NOT_APPLICABLE 不猜，误报/正常完成按观测 PASS")
    void recordLoopPersistsObservationSubset() {
        Capturing cap = seededProjection();
        UUID evalRunId = UUID.randomUUID();
        UUID caseResultId = UUID.randomUUID();

        scorer(cap).recordLoop(evalRunId, golden, 1, UUID.randomUUID(), caseResultId);

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(0)).isEqualTo(caseResultId);
        assertThat(row.get(1)).isEqualTo(evalRunId);
        assertThat(row.get(2)).isEqualTo("S1");
        assertThat(row.get(4)).isEqualTo("loop-behavior-v1");
        assertThat(row.get(5)).isEqualTo("COMPLETED");
        assertThat(row.get(6)).isEqualTo("null"); // detection_event_index 未检出
        assertThat(row.get(8)).isEqualTo(0);      // post_stop_new_actions
        assertThat(row.get(9)).isEqualTo(2L);     // onset 起算物理调用（含失败实发）
        assertThat(row.get(10)).isEqualTo("null"); // 工具行 token 缺失 → 不出数
        assertThat(row.get(11)).isEqualTo("60");   // T0 → T0+60
        String checks = (String) row.get(12);
        assertThat(checks)
                .contains("\"name\":\"loop_detection\"")
                .contains("\"name\":\"safe_stop\"")
                .contains("\"name\":\"loop_false_positive\"")
                .contains("\"name\":\"normal_task_completion\"")
                .contains("\"status\":\"NOT_APPLICABLE\"")
                .contains("\"status\":\"PASS\"")
                .doesNotContain("\"status\":\"FAIL\"");
    }

    @Test
    @DisplayName("trace 缺失（rcaRunId null）→ 五项检查全 NOT_ASSESSED（NO_EVENTS），不猜通过")
    void recordLoopWithoutTraceMarksNotAssessed() {
        Capturing cap = new Capturing();

        scorer(cap).recordLoop(UUID.randomUUID(), golden, 1, null, UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("null"); // stop_reason 无可评轨迹
        String checks = (String) row.get(12);
        assertThat(checks).contains("NO_EVENTS")
                .doesNotContain("\"status\":\"PASS\"")
                .doesNotContain("\"status\":\"FAIL\"");
        assertThat((String) row.get(14)).contains("TRACE_NO_EVENTS");
    }

    @Test
    @DisplayName("投影读失败 → ERROR 行（五项检查 TRACE_READ_ERROR），不冒充零问题通过")
    void recordLoopReadFailureLandsErrorRow() {
        Capturing cap = seededProjection();
        cap.failReads = true;

        scorer(cap).recordLoop(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("null");
        String checks = (String) row.get(12);
        assertThat(checks).contains("\"status\":\"ERROR\"").contains("TRACE_READ_ERROR")
                .doesNotContain("\"status\":\"PASS\"");
        assertThat((String) row.get(14)).contains("TRACE_READ_ERROR");
    }

    @Test
    @DisplayName("落库失败不回滚评分主链（fail-soft：异常吞掉，缺席=未评如实）")
    void recordLoopInsertFailureDoesNotPropagate() {
        Capturing cap = seededProjection();
        cap.failInsert = true;

        assertThatCode(() -> scorer(cap).recordLoop(UUID.randomUUID(), golden, 1,
                UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(cap.rows).isEmpty();
    }
}

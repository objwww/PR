package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.SynonymLexicon;
import com.objwww.pr.control.eval.domain.model.CollaborationInput;
import com.objwww.pr.control.eval.domain.repository.EvalCaseCollabSink;
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
 * ME-T12a（D07）SingleCaseScorer.recordCollab 终态收尾装配：只读观测投影
 * （rca_delegation_decision 交接边 + rca_delegation_receipt 回执 + rca_model_call
 * 角色成本 + rca_task 取消围栏推导）→ CollaborationEvaluator 纯函数 → V163 落行；
 * 未观测字段 null → NOT_ASSESSED；零交接边 → NOT_APPLICABLE；投影读失败落
 * ERROR 行；落库失败不回滚评分主链；sink 缺席 = 不落。
 */
class SingleCaseScorerCollabTest {

    private static final TypedRootCause EXPECTED =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID PRIMARY = UUID.randomUUID();
    private static final UUID CHILD = UUID.randomUUID();
    private static final UUID DECISION = UUID.randomUUID();
    private static final UUID RECEIPT = UUID.randomUUID();

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
        List<SingleCaseScorer.CollabDecisionRow> decisions = List.of();
        List<SingleCaseScorer.CollabReceiptRow> receipts = List.of();
        List<SingleCaseScorer.CollabTaskRow> tasks = List.of();
        List<SingleCaseScorer.LoopModelCallRow> modelCalls = List.of();
        boolean failReads;
        boolean failInsert;

        final EvalCaseCollabSink sink = new EvalCaseCollabSink() {
            @Override
            public void insert(UUID caseResultId, UUID evalRunId, String scenarioId,
                               int roundNo, String graderVersion, Integer edgeCount,
                               Integer admittedCount, Long tokenCostTotal,
                               String checksJson, String metricsJson,
                               String failureLabelsJson, String suspectedJson,
                               String supportedJson) {
                if (failInsert) {
                    throw new IllegalStateException("insert down");
                }
                rows.add(List.of(caseResultId, evalRunId, scenarioId, roundNo,
                        graderVersion, String.valueOf(edgeCount),
                        String.valueOf(admittedCount), String.valueOf(tokenCostTotal),
                        checksJson, metricsJson, failureLabelsJson, suspectedJson,
                        supportedJson));
            }
        };
    }

    /** 假 jdbc：子类覆写四条只读投影缝返回假行（14 形构造器，collabSink 在末尾） */
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
                null, null, null, null, null, null, null, null, cap.sink) {
            @Override
            List<CollabDecisionRow> collabDecisions(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.decisions;
            }

            @Override
            List<CollabReceiptRow> collabReceipts(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.receipts;
            }

            @Override
            List<CollabTaskRow> collabTasks(UUID rcaRunId) {
                if (cap.failReads) {
                    throw new IllegalStateException("read down");
                }
                return cap.tasks;
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

    /** 标准交接投影：一 APPROVED 决策 + ACCEPTED 回执 + 角色成本 150（100+50） */
    private Capturing seededProjection() {
        Capturing cap = new Capturing();
        cap.decisions = List.of(new SingleCaseScorer.CollabDecisionRow(DECISION, PRIMARY,
                1, 0, "gap-metrics", "metrics-expert", CHILD, T0));
        cap.receipts = List.of(new SingleCaseScorer.CollabReceiptRow(RECEIPT, CHILD,
                "SUCCEEDED", "ACCEPTED", "[\"pd-1\"]", T0.plusSeconds(60)));
        cap.tasks = List.of(
                new SingleCaseScorer.CollabTaskRow(PRIMARY, "DONE", T0.plusSeconds(120)),
                new SingleCaseScorer.CollabTaskRow(CHILD, "DONE", T0.plusSeconds(90)));
        cap.modelCalls = List.of(
                new SingleCaseScorer.LoopModelCallRow(UUID.randomUUID(), CHILD, 1, 1,
                        "SUCCESS", null, "{\"total_tokens\":100}", T0.plusSeconds(10)),
                new SingleCaseScorer.LoopModelCallRow(UUID.randomUUID(), CHILD, 2, 1,
                        "SUCCESS", null, "{\"total_tokens\":50}", T0.plusSeconds(20)));
        return cap;
    }

    @Test
    @DisplayName("投影装配：决策+回执+模型成本归并交接边——outcome/admission/成本/取证 digest 正确，未观测面 null")
    void assembleCollabInputProjectsHandoffEdge() {
        Capturing cap = seededProjection();
        UUID rcaRunId = UUID.randomUUID();

        CollaborationInput in = scorer(cap).assembleCollabInput(rcaRunId);

        assertThat(in.caseId()).isEqualTo(rcaRunId.toString());
        assertThat(in.collaborationNeeded()).isNull();
        assertThat(in.primaryCancelled()).isFalse();
        assertThat(in.handoffs()).hasSize(1);
        CollaborationInput.HandoffEdge e = in.handoffs().get(0);
        assertThat(e.edgeId()).isEqualTo(DECISION.toString());
        assertThat(e.parentTaskId()).isEqualTo(PRIMARY.toString());
        assertThat(e.childTaskId()).isEqualTo(CHILD.toString());
        assertThat(e.roleId()).isEqualTo("metrics-expert");
        assertThat(e.batchId()).isEqualTo(1);
        assertThat(e.inputGaps()).containsExactly("gap-metrics");
        assertThat(e.outcome()).isEqualTo(CollaborationInput.ChildOutcome.SUCCEEDED);
        assertThat(e.admission()).isEqualTo(CollaborationInput.Admission.ACCEPTED);
        assertThat(e.sentFacts()).as("交接内容生产未观测").isNull();
        assertThat(e.consumedByPrimary()).isNull();
        assertThat(e.consumptionCount()).isNull();
        assertThat(e.applicableEvidence()).isNull();
        assertThat(e.tokenCost()).isEqualTo(150L);
        assertThat(e.terminationReason()).isNull();
        assertThat(e.fetchedEvidenceDigests()).containsExactly("pd-1");
        assertThat(e.dispatchedAfterCancel()).isFalse();
        assertThat(e.mergedAfterCancel()).isFalse();
    }

    @Test
    @DisplayName("终态协作落档：未观测面检查 NOT_ASSESSED 不猜，角色成本指标出数，标量合计正确")
    void recordCollabPersistsObservationSubset() {
        Capturing cap = seededProjection();
        UUID evalRunId = UUID.randomUUID();
        UUID caseResultId = UUID.randomUUID();

        scorer(cap).recordCollab(evalRunId, golden, 1, UUID.randomUUID(), caseResultId);

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(0)).isEqualTo(caseResultId);
        assertThat(row.get(1)).isEqualTo(evalRunId);
        assertThat(row.get(4)).isEqualTo("collaboration-v1");
        assertThat(row.get(5)).isEqualTo("1");   // edge_count
        assertThat(row.get(6)).isEqualTo("1");   // admitted_count
        assertThat(row.get(7)).isEqualTo("150"); // token_cost_total
        String checks = (String) row.get(8);
        assertThat(checks)
                .contains("\"name\":\"delegation_necessity_choice\"")
                .contains("\"name\":\"handoff_fact_constraint_retention\"")
                .contains("NEED_LABEL_MISSING")
                .contains("HANDOFF_CONTENT_UNOBSERVED")
                .contains("RECEIPT_CONSUMPTION_UNOBSERVED")
                .contains("APPLICABILITY_UNOBSERVED")
                .contains("\"status\":\"NOT_ASSESSED\"")
                .contains("\"status\":\"NOT_APPLICABLE\"")
                .doesNotContain("\"status\":\"FAIL\"")
                .doesNotContain("\"status\":\"PASS\"");
        assertThat((String) row.get(9)).contains("role_token_cost");
        assertThat((String) row.get(11)).isEqualTo("[]"); // suspected 无失败无归因
        assertThat((String) row.get(12)).isEqualTo("[]"); // supported
    }

    @Test
    @DisplayName("零交接边 run → 机制面 NOT_APPLICABLE 如实（edge_count=0），不混同 trace 缺失")
    void recordCollabZeroHandoffsNotApplicable() {
        Capturing cap = new Capturing();
        cap.tasks = List.of(new SingleCaseScorer.CollabTaskRow(PRIMARY, "DONE", T0));

        scorer(cap).recordCollab(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("0");
        assertThat(row.get(7)).isEqualTo("0");
        String checks = (String) row.get(8);
        assertThat(checks).contains("NO_HANDOFF").contains("NO_RECEIPT")
                .doesNotContain("TRACE_MISSING")
                .doesNotContain("\"status\":\"FAIL\"");
    }

    @Test
    @DisplayName("trace 缺失（rcaRunId null）→ 十三项检查全 NOT_ASSESSED（TRACE_MISSING），标量不出数")
    void recordCollabWithoutTraceMarksNotAssessed() {
        Capturing cap = new Capturing();

        scorer(cap).recordCollab(UUID.randomUUID(), golden, 1, null, UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("null"); // edge_count 未观测
        assertThat(row.get(7)).isEqualTo("null"); // token_cost_total 未观测
        String checks = (String) row.get(8);
        assertThat(checks).contains("TRACE_MISSING")
                .doesNotContain("\"status\":\"PASS\"")
                .doesNotContain("\"status\":\"NOT_APPLICABLE\"");
        assertThat((String) row.get(10)).contains("TRACE_MISSING");
    }

    @Test
    @DisplayName("取消围栏推导：主任务 CANCELLED 后新派发 → cancellation_fence FAIL + MAST_TASK_DERAIL 疑似归因")
    void recordCollabCancellationFenceDerived() {
        Capturing cap = seededProjection();
        // 主任务 T0+30 取消，决策 T0+40 才派发（围栏违例）
        cap.decisions = List.of(new SingleCaseScorer.CollabDecisionRow(DECISION, PRIMARY,
                1, 0, "gap-metrics", "metrics-expert", CHILD, T0.plusSeconds(40)));
        cap.tasks = List.of(
                new SingleCaseScorer.CollabTaskRow(PRIMARY, "CANCELLED", T0.plusSeconds(30)),
                new SingleCaseScorer.CollabTaskRow(CHILD, "DONE", T0.plusSeconds(90)));

        scorer(cap).recordCollab(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        String checks = (String) row.get(8);
        assertThat(checks).contains("DISPATCH_AFTER_CANCEL").contains("\"status\":\"FAIL\"");
        assertThat((String) row.get(10)).contains("MAST_TASK_DERAIL");
        assertThat((String) row.get(11)).contains("疑似归因");
        assertThat((String) row.get(12)).isEqualTo("[]"); // 无干预对照不进 supported
    }

    @Test
    @DisplayName("投影读失败 → ERROR 行（十三项检查 TRACE_READ_ERROR），标量不出数，不冒充零问题通过")
    void recordCollabReadFailureLandsErrorRow() {
        Capturing cap = seededProjection();
        cap.failReads = true;

        scorer(cap).recordCollab(UUID.randomUUID(), golden, 1, UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(cap.rows).hasSize(1);
        List<Object> row = cap.rows.getFirst();
        assertThat(row.get(5)).isEqualTo("null");
        String checks = (String) row.get(8);
        assertThat(checks).contains("\"status\":\"ERROR\"").contains("TRACE_READ_ERROR")
                .doesNotContain("\"status\":\"PASS\"");
        assertThat((String) row.get(10)).contains("TRACE_READ_ERROR");
    }

    @Test
    @DisplayName("落库失败不回滚评分主链（fail-soft：异常吞掉，缺席=未评如实）")
    void recordCollabInsertFailureDoesNotPropagate() {
        Capturing cap = seededProjection();
        cap.failInsert = true;

        assertThatCode(() -> scorer(cap).recordCollab(UUID.randomUUID(), golden, 1,
                UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(cap.rows).isEmpty();
    }
}

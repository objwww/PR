package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC21/23 回执准入单事务封闭裁决（§20.1）：messageId 幂等（重复投递恰一次合并）、
 * 超大回执显式拒绝载荷不落库、run 终态后迟到仅审计不合入（W1 不冒充新现场）、
 * 身份面（对不上既成裁决=REJECTED_SHAPE）、结构契约（FAILED 必带结构化缺口）。
 */
class DelegationReceiptServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private final UUID runId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();
    private final UUID primaryTaskId = UUID.randomUUID();
    private final UUID childTaskId = UUID.randomUUID();
    private final UUID decisionId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    private final MemReceipts receipts = new MemReceipts();
    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Tasks tasks = new AlertInMemoryStores.Tasks();
    private final MemDecisions decisions = new MemDecisions();

    private DelegationReceiptService service;

    @BeforeEach
    void wire() {
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("materials"), NOW, NOW, NOW,
                null, null));
        tasks.insert(new RcaTask(primaryTaskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.RUNNING, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0));
        tasks.insert(new RcaTask(childTaskId, runId, "DELEGATE-g-logs",
                RcaTaskState.RUNNING, 5, NOW, NOW, Instant.MAX, null, null, 0, 1, 2,
                NOW, NOW, 1));
        decisions.rows.add(new DelegationDecision(decisionId, runId, primaryTaskId, 1,
                0, "g-logs", "logs", "1", "查错误日志",
                DelegationDecision.Status.APPROVED, null, childTaskId, NOW));
        service = new DelegationReceiptService(receipts, runs, tasks, decisions,
                directTx(), () -> NOW, new ObjectMapper());
    }

    private DelegationReceiptService.Submission submission() {
        return new DelegationReceiptService.Submission(messageId, runId, decisionId,
                childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("error_rate=0.98"), List.of("e-1"), List.of(), List.of());
    }

    @Test
    @DisplayName("MC21：同 messageId 重复投递恰一行，有效结果只合入一次")
    void duplicateSubmissionMergesExactlyOnce() {
        DelegationReceiptService.Verdict first = service.submit(submission());
        DelegationReceiptService.Verdict again = service.submit(submission());

        assertThat(first.duplicate()).isFalse();
        assertThat(first.merged()).isTrue();
        assertThat(again.duplicate()).as("重复投递返回既有行").isTrue();
        assertThat(again.receipt().id()).isEqualTo(first.receipt().id());
        assertThat(receipts.rows).as("台账不落第二行").hasSize(1);
    }

    @Test
    @DisplayName("MC21：超大回执显式拒绝，载荷不落库但 digest/字节留痕")
    void oversizedReceiptRejectedWithoutPayload() {
        DelegationReceiptService.Submission huge = new DelegationReceiptService.Submission(
                messageId, runId, decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("X".repeat(70_000)), List.of("e-1"), List.of(), List.of());

        DelegationReceiptService.Verdict verdict = service.submit(huge);

        assertThat(verdict.merged()).isFalse();
        DelegationReceipt row = verdict.receipt();
        assertThat(row.admission()).isEqualTo(DelegationReceipt.Admission.OVERSIZED);
        assertThat(row.findings()).as("无内存失控：载荷不落库").isEmpty();
        assertThat(row.payloadBytes()).isGreaterThan(
                DelegationReceiptService.MAX_RECEIPT_BYTES);
        assertThat(row.payloadDigest()).hasSize(64);
    }

    @Test
    @DisplayName("MC23：run 终态后迟到回执仅审计不合入（W1 不冒充新现场）")
    void lateReceiptAfterRunTerminalIsAuditOnly() {
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                RcaRunState.FAILED, Digest.sha256Of("materials"), NOW, NOW, NOW,
                NOW, "expired"));

        DelegationReceiptService.Verdict verdict = service.submit(submission());

        assertThat(verdict.receipt().admission()).isEqualTo(DelegationReceipt.Admission.LATE);
        assertThat(verdict.merged()).as("迟到审计不合入有效记忆").isFalse();
        assertThat(verdict.receipt().findings()).as("审计行保留有界载荷可追认")
                .containsExactly("error_rate=0.98");
    }

    @Test
    @DisplayName("身份面：对不上既成 APPROVED 裁决 → REJECTED_SHAPE 审计")
    void receiptWithoutMatchingDecisionIsShapeRejected() {
        DelegationReceiptService.Submission stranger = new DelegationReceiptService.Submission(
                messageId, runId, UUID.randomUUID(), childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("f"), List.of("e-1"), List.of(), List.of());

        DelegationReceiptService.Verdict verdict = service.submit(stranger);

        assertThat(verdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.REJECTED_SHAPE);
        assertThat(verdict.receipt().missingInformation().get(0)).contains("APPROVED");
    }

    @Test
    @DisplayName("结构契约：FAILED 无缺口清单 → REJECTED_SHAPE（失败必带结构化缺口）")
    void failedReceiptWithoutGapIsShapeRejected() {
        DelegationReceiptService.Submission failed = new DelegationReceiptService.Submission(
                messageId, runId, decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.FAILED,
                List.of(), List.of(), List.of(), List.of());

        DelegationReceiptService.Verdict verdict = service.submit(failed);

        assertThat(verdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.REJECTED_SHAPE);
        assertThat(verdict.receipt().missingInformation().toString())
                .contains("结构化缺口");
    }

    @Test
    @DisplayName("结构契约：四清单全空 → REJECTED_SHAPE；ACCEPTED+FAILED 有缺口合法")
    void acceptedFailedWithGapIsValid() {
        DelegationReceiptService.Submission empty = new DelegationReceiptService.Submission(
                messageId, runId, decisionId, childTaskId, 1,
                DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of(), List.of(), List.of(), List.of());
        assertThat(service.submit(empty).receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.REJECTED_SHAPE);

        DelegationReceiptService.Submission failedWithGap =
                DelegationReceiptService.Submission.of(UUID.randomUUID(), runId,
                        decisionId, childTaskId, 1, DelegationReceipt.ChildStatus.FAILED,
                        List.of(), List.of(), List.of("logs 查询超时，未产出结论"));
        DelegationReceiptService.Verdict verdict = service.submit(failedWithGap);

        assertThat(verdict.merged()).isTrue();
        assertThat(verdict.receipt().missingInformation())
                .containsExactly("logs 查询超时，未产出结论");
    }

    @Test
    @DisplayName("子任务归属：round 与回执声明不一致 → REJECTED_SHAPE")
    void roundMismatchIsIdentityRejection() {
        DelegationReceiptService.Submission wrongRound =
                new DelegationReceiptService.Submission(messageId, runId, decisionId,
                        childTaskId, 2, DelegationReceipt.ChildStatus.SUCCEEDED,
                        List.of("f"), List.of("e-1"), List.of(), List.of());

        DelegationReceiptService.Verdict verdict = service.submit(wrongRound);

        assertThat(verdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.REJECTED_SHAPE);
        assertThat(verdict.receipt().missingInformation().get(0)).contains("run/round");
    }

    // ------------------------------------------------------------------ 假件

    /** 直通事务桩：准入逻辑纯内存，无回滚语义需求 */
    private static TransactionOperations directTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }

            @Override
            public void executeWithoutResult(Consumer<TransactionStatus> action) {
                action.accept(null);
            }
        };
    }

    static final class MemReceipts implements DelegationReceiptRepository {
        final List<DelegationReceipt> rows = new ArrayList<>();

        @Override
        public void insert(DelegationReceipt receipt) {
            rows.add(receipt);
        }

        @Override
        public Optional<DelegationReceipt> findByMessageId(UUID id) {
            return rows.stream().filter(r -> r.messageId().equals(id)).findFirst();
        }

        @Override
        public List<DelegationReceipt> findAcceptedByRunAndRound(UUID run,
                UUID primaryTask, int round) {
            return rows.stream().filter(r -> r.admission() == DelegationReceipt.Admission.ACCEPTED
                    && r.runId().equals(run)
                    && r.primaryTaskId().equals(primaryTask)
                    && r.roundId() == round).toList();
        }

        @Override
        public List<DelegationReceipt> findByChildTaskId(UUID child) {
            return rows.stream().filter(r -> r.childTaskId().equals(child)).toList();
        }
    }

    static final class MemDecisions implements DelegationDecisionRepository {
        final List<DelegationDecision> rows = new ArrayList<>();

        @Override
        public void insert(DelegationDecision decision) {
            rows.add(decision);
        }

        @Override
        public Optional<DelegationDecision> findById(UUID id) {
            return rows.stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public Optional<DelegationDecision> findByRunAndGap(UUID run, String gapId) {
            return rows.stream()
                    .filter(d -> d.runId().equals(run) && d.gapId().equals(gapId))
                    .findFirst();
        }

        @Override
        public List<DelegationDecision> findByRunAndPrimaryTask(UUID run,
                UUID primaryTask) {
            return rows;
        }
    }
}

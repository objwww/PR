package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.statemachine.IllegalCaseActionException;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * OperatorCaseService 单测（M5-11）：幂等合并（(tenant,fingerprint) 聚集不新建单）、
 * 命令面 CAS（expected-revision 冲突显式）、SLA 升级恰一次、idempotency-key 重放零副作用。
 * InMemory fake 模拟 CAS；并发恰一成功由真 PG IT 面兜底（PostgresOperatorCaseIT）。
 */
class OperatorCaseServiceTest {

    private static final Supplier<Instant> CLOCK =
            () -> Instant.parse("2026-09-08T10:00:00Z");

    private final InMemoryOperatorCases repo = new InMemoryOperatorCases();
    private final OperatorCaseService service = new OperatorCaseService(repo, CLOCK);

    // ------------------------------------------------------------------ 合并幂等（连续三次聚集）

    @Test
    void openCreatesCaseWithCreationAuditAndSingleRevision() {
        OperatorCaseService.MergeOutcome outcome = service.openOrMerge(draft("fp-1", "idem-1"));

        assertThat(outcome.created()).isTrue();
        OperatorCase c = repo.byId(outcome.caseId());
        assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.revision()).isEqualTo(1);
        assertThat(c.audits()).hasSize(1);
        assertThat(c.audits().get(0).action()).isEqualTo("CASE_CREATED");
        assertThat(c.audits().get(0).key()).isEqualTo("idem-1");
        assertThat(c.activities()).hasSize(1);
    }

    @Test
    void threeOccurrencesAggregateIntoOneCase() {
        OperatorCaseService.MergeOutcome first = service.openOrMerge(draft("fp-1", "idem-1"));
        service.openOrMerge(draft("fp-1", "idem-2"));
        OperatorCaseService.MergeOutcome third = service.openOrMerge(draft("fp-1", "idem-3"));

        assertThat(first.caseId()).isEqualTo(third.caseId());
        assertThat(third.created()).isFalse();
        assertThat(repo.size()).as("同 fingerprint 三次发生只有一单").isEqualTo(1);
        OperatorCase c = repo.byId(third.caseId());
        assertThat(c.revision()).isEqualTo(3);
        assertThat(c.activities()).hasSize(3);
        assertThat(c.audits()).hasSize(3);
        assertThat(c.audits().get(2).action()).isEqualTo("CASE_MERGED");
    }

    @Test
    void mergeReplayWithSameKeyDoesNotBump() {
        service.openOrMerge(draft("fp-1", "idem-1"));
        service.openOrMerge(draft("fp-1", "idem-2"));
        OperatorCaseService.MergeOutcome replay = service.openOrMerge(draft("fp-1", "idem-2"));

        assertThat(replay.created()).isFalse();
        OperatorCase c = repo.byId(replay.caseId());
        assertThat(c.revision()).as("重放零副作用").isEqualTo(2);
        assertThat(c.activities()).hasSize(2);
    }

    @Test
    void mergeIntoResolvedCaseRecordsRecurrenceWithoutReopening() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");
        service.resolve(id, 2, "WONT_FIX", "误报", "operator-a", "k-resolve");

        OperatorCaseService.MergeOutcome merged = service.openOrMerge(draft("fp-1", "idem-2"));

        OperatorCase c = repo.byId(merged.caseId());
        assertThat(c.status()).as("复发不改写结案状态").isEqualTo(CaseStatus.RESOLVED);
        assertThat(c.revision()).isEqualTo(4);
        assertThat(c.activities()).hasSize(4);
    }

    // ------------------------------------------------------------------ 命令面 CAS 与迁移

    @Test
    void claimTransitionsOpenToAckedAndSetsOwner() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();

        OperatorCase claimed = service.claim(id, 1, "operator-a", "k-claim");

        assertThat(claimed.status()).isEqualTo(CaseStatus.ACKED);
        assertThat(claimed.owner()).isEqualTo("operator-a");
        assertThat(claimed.revision()).isEqualTo(2);
        assertThat(claimed.audits().get(1).action()).isEqualTo("CLAIM");
    }

    @Test
    void commandWithStaleRevisionConflictsWithoutSideEffect() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        // 先 assign（OPEN 态内合法动作，revision 升到 2），再以旧 revision 1 claim——
        // 状态机放行、CAS 拒绝：败者零副作用（并发恰一成功的物理前提）
        service.assign(id, 1, "operator-a", "system", "k-assign");

        assertThatExceptionOfType(CaseRevisionConflictException.class).isThrownBy(
                () -> service.claim(id, 1, "operator-b", "k-claim"));

        OperatorCase c = repo.byId(id);
        assertThat(c.owner()).as("CAS 败者零副作用").isEqualTo("operator-a");
        assertThat(c.revision()).isEqualTo(2);
    }

    @Test
    void claimOnAckedCaseIsIllegalTransition() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");

        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> service.claim(id, 2, "operator-b", "k-claim-2"));
    }

    @Test
    void staleRevisionReaderOnTerminalStatusGetsConflictBeforeStateMachine() {
        // BA-45（195 真 PG 实证，PostgresOperatorCaseIT 并发认领连坐）：并发败者在胜者
        // 提交后才读——期望修订=1 而现行=2（ACKED）。修订冲突必须先于状态机判定
        //（CAS 先行），否则时序露窗即抛 IllegalCaseActionException；败者零副作用
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");

        assertThatExceptionOfType(CaseRevisionConflictException.class).isThrownBy(
                () -> service.claim(id, 1, "operator-b", "k-claim-b"));

        assertThat(repo.byId(id).revision()).as("败者零副作用").isEqualTo(2);
        assertThat(repo.byId(id).owner()).isEqualTo("operator-a");
    }

    @Test
    void commandReplayWithSameKeyReturnsCurrentState() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");

        OperatorCase replay = service.claim(id, 2, "operator-a", "k-claim");

        assertThat(replay.revision()).as("幂等重放不再 bump").isEqualTo(2);
        assertThat(replay.audits()).hasSize(2);
    }

    @Test
    void resolveRequiresStructuredReasonAndStoresIt() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                () -> service.resolve(id, 2, " ", "备注", "operator-a", "k-r"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                () -> service.resolve(id, 2, "WONT_FIX", " ", "operator-a", "k-r"));

        OperatorCase resolved = service.resolve(id, 2, "WONT_FIX", "误报聚合计数", "operator-a", "k-r");
        assertThat(resolved.status()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(resolved.resolution().code()).isEqualTo("WONT_FIX");
        assertThat(resolved.resolution().note()).isEqualTo("误报聚合计数");
    }

    @Test
    void resolveOnResolvedCaseIsIllegal() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.claim(id, 1, "operator-a", "k-claim");
        service.resolve(id, 2, "WONT_FIX", "误报", "operator-a", "k-resolve");

        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> service.resolve(id, 3, "FIXED", "再修", "operator-a", "k-r2"));
    }

    @Test
    void assignChangesOwnerWithoutStatusChange() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();

        OperatorCase assigned = service.assign(id, 1, "operator-b", "system", "k-assign");

        assertThat(assigned.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(assigned.owner()).isEqualTo("operator-b");
        assertThat(assigned.revision()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ SLA 升级恰一次

    @Test
    void escalateBumpsRevisionWithSlaEscalatedAuditWithoutStatusChange() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();

        OperatorCase escalated = service.escalate(id, "system", "k-esc");

        assertThat(escalated.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(escalated.revision()).isEqualTo(2);
        assertThat(escalated.audits().get(1).action()).isEqualTo("SLA_ESCALATED");
        assertThat(escalated.audits().get(1).key()).isEqualTo("k-esc");
    }

    @Test
    void escalateReplayWithSameKeyBumpsExactlyOnce() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.escalate(id, "system", "k-esc");
        service.escalate(id, "system", "k-esc");
        service.escalate(id, "system", "k-esc");

        OperatorCase c = repo.byId(id);
        assertThat(c.revision()).as("同 key 三次重放只 bump 一次").isEqualTo(2);
        assertThat(c.audits().stream().filter(a -> a.action().equals("SLA_ESCALATED"))).hasSize(1);
    }

    @Test
    void escalateOnResolvedCaseIsIllegal() {
        UUID id = service.openOrMerge(draft("fp-1", "idem-1")).caseId();
        service.resolve(id, 1, "WONT_FIX", "误报", "operator-a", "k-resolve");

        assertThatExceptionOfType(IllegalCaseActionException.class).isThrownBy(
                () -> service.escalate(id, "system", "k-esc"));
    }

    // ------------------------------------------------------------------ 未知 Case

    @Test
    void unknownCaseIsNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatExceptionOfType(CaseNotFoundException.class)
                .isThrownBy(() -> service.claim(missing, 1, "operator-a", "k"));
        assertThatExceptionOfType(CaseNotFoundException.class)
                .isThrownBy(() -> service.escalate(missing, "system", "k"));
    }

    // ------------------------------------------------------------------ 种子

    private CaseDraft draft(String fingerprint, String key) {
        return new CaseDraft("tenant-1", fingerprint, "Claim 冲突", "P0", "CLAIM_CONFLICT",
                UUID.randomUUID(), "root-cause", "payment-failure",
                Digest.sha256Of("snapshot-" + fingerprint), 13,
                List.of("evidence#81"), CLOCK.get().plusSeconds(600), CLOCK.get().plusSeconds(3600),
                key);
    }
}

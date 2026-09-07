package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.model.GoldenCandidate;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.control.eval.domain.model.GoldenReviewAction;
import com.objwww.pr.control.eval.domain.model.GoldenReviewEvent;
import com.objwww.pr.control.eval.domain.repository.GoldenCandidateRepository;
import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GoldenCandidateService 双人复核编排 UT（M5-03；INV-AM5-2）：
 * 同人双签拒绝、状态机无旁路（decide on DRAFT / withdraw on 终态）、
 * revision CAS 失败不落任何状态、幂等重放不重复施加、提案校验。
 * 仓储为进程内 fake（真 PG 事务面由 PostgresGoldenCandidateRepositoryTest 覆盖）。
 */
class GoldenCandidateServiceTest {

    private final FakeRepo repo = new FakeRepo();
    private final GoldenCandidateService service = new GoldenCandidateService(repo);

    private UUID caseVersionId = UUID.randomUUID();

    private GoldenCandidate propose() {
        return service.propose(caseVersionId, Map.of("root_cause", "duplicate-payment"),
                "E2E 观测复核提案", "proposer-1");
    }

    @Test
    void proposeInsertsDraftWithEventAndZeroRevision() {
        GoldenCandidate draft = propose();
        assertThat(draft.state()).isEqualTo(GoldenCandidateState.DRAFT);
        assertThat(draft.revision()).isZero();
        assertThat(draft.reviewerA()).isNull();
        assertThat(draft.reviewerB()).isNull();
        assertThat(repo.candidates).containsKey(draft.id());
        assertThat(repo.events).hasSize(1);
        assertThat(repo.events.get(0).action()).isEqualTo(GoldenReviewAction.PROPOSED);
    }

    @Test
    void proposeRejectsBlankReasonAndEmptyGt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.propose(caseVersionId, Map.of("root_cause", "x"),
                        " ", "proposer-1"))
                .withMessageContaining("reason");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.propose(caseVersionId, Map.of(), "理由", "proposer-1"))
                .withMessageContaining("proposedGt");
    }

    @Test
    void submitMovesDraftToReviewWithBumpedRevision() {
        GoldenCandidate draft = propose();
        service.submit(draft.id(), "proposer-1", 0, "idem-submit-1");

        GoldenCandidate inReview = repo.candidates.get(draft.id());
        assertThat(inReview.state()).isEqualTo(GoldenCandidateState.REVIEW);
        assertThat(inReview.revision()).isEqualTo(1L);
        assertThat(repo.events).hasSize(2);
        assertThat(repo.events.get(1).action()).isEqualTo(GoldenReviewAction.SUBMITTED);
    }

    @Test
    void samePersonDualSignRejectedBeforeAnythingIsWritten() {
        GoldenCandidate draft = propose();
        service.submit(draft.id(), "proposer-1", 0, "idem-submit-1");
        int eventsBefore = repo.events.size();

        assertThatIllegalArgumentException()
                .as("INV-AM5-2：同人不能双签")
                .isThrownBy(() -> service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                        "reviewer-1", "reviewer-1", 1, "idem-decide-1"))
                .withMessageContaining("同人不能双签");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                        " ", "reviewer-1", 1, "idem-decide-2"))
                .withMessageContaining("reviewerA");

        // 拒绝后零副作用：状态仍 REVIEW、revision 仍 1、无新事件
        assertThat(repo.candidates.get(draft.id()).state()).isEqualTo(GoldenCandidateState.REVIEW);
        assertThat(repo.candidates.get(draft.id()).revision()).isEqualTo(1L);
        assertThat(repo.events).hasSize(eventsBefore);
    }

    @Test
    void publishAndRejectRequireTwoDistinctReviewers() {
        GoldenCandidate draft = propose();
        service.submit(draft.id(), "proposer-1", 0, "idem-s");

        service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                "reviewer-1", "reviewer-2", 1, "idem-p");
        GoldenCandidate published = repo.candidates.get(draft.id());
        assertThat(published.state()).isEqualTo(GoldenCandidateState.PUBLISHED);
        assertThat(published.reviewerA()).isEqualTo("reviewer-1");
        assertThat(published.reviewerB()).isEqualTo("reviewer-2");
        assertThat(published.revision()).isEqualTo(2L);
        assertThat(repo.events.get(2).action()).isEqualTo(GoldenReviewAction.PUBLISHED);

        // 拒绝路径同要求
        GoldenCandidate draft2 = propose();
        service.submit(draft2.id(), "proposer-1", 0, "idem-s2");
        service.decide(draft2.id(), GoldenCandidateService.Decision.REJECT,
                "reviewer-1", "reviewer-2", 1, "idem-r");
        assertThat(repo.candidates.get(draft2.id()).state())
                .isEqualTo(GoldenCandidateState.REJECTED);
    }

    @Test
    void bypassAttemptsRejectedByStateMachine() {
        GoldenCandidate draft = propose();

        // DRAFT 直达结论 = 跳级旁路
        assertThatThrownBy(() -> service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                "reviewer-1", "reviewer-2", 0, "idem-b1"))
                .isInstanceOf(IllegalTransitionException.class);
        // WITHDRAWN 复活
        service.withdraw(draft.id(), "proposer-1", 0, "idem-w");
        assertThatThrownBy(() -> service.submit(draft.id(), "proposer-1", 1, "idem-w2"))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> service.decide(draft.id(), GoldenCandidateService.Decision.REJECT,
                "reviewer-1", "reviewer-2", 1, "idem-w3"))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void casFailureLeavesNoTrace() {
        GoldenCandidate draft = propose();
        service.submit(draft.id(), "proposer-1", 0, "idem-s");
        int eventsBefore = repo.events.size();

        // 期望 revision 过期（实际已 1）→ 仓储 CAS 0 行 → fail-loud
        assertThatIllegalStateException()
                .isThrownBy(() -> service.decide(draft.id(), GoldenCandidateService.Decision.PUBLISH,
                        "reviewer-1", "reviewer-2", 0, "idem-cas"))
                .withMessageContaining("CAS");
        assertThat(repo.candidates.get(draft.id()).state()).isEqualTo(GoldenCandidateState.REVIEW);
        assertThat(repo.events).hasSize(eventsBefore);
    }

    @Test
    void idempotentReplayDoesNotApplyTwice() {
        GoldenCandidate draft = propose();
        service.submit(draft.id(), "proposer-1", 0, "idem-same");
        int eventsAfterFirst = repo.events.size();

        service.submit(draft.id(), "proposer-1", 1, "idem-same");
        assertThat(repo.events).as("同 idempotencyKey 重放 = 空操作").hasSize(eventsAfterFirst);
        assertThat(repo.candidates.get(draft.id()).revision()).isEqualTo(1L);
    }

    /** 进程内 fake：忠实模拟 casTransition 的三重 CAS（id+expectedState+revision） */
    private static final class FakeRepo implements GoldenCandidateRepository {
        private final Map<UUID, GoldenCandidate> candidates = new HashMap<>();
        private final List<GoldenReviewEvent> events = new ArrayList<>();
        private final java.util.Set<String> eventKeys = new java.util.HashSet<>();

        @Override
        public void insertDraft(GoldenCandidate candidate, GoldenReviewEvent event) {
            candidates.put(candidate.id(), candidate);
            events.add(event);
            eventKeys.add(event.idempotencyKey());
        }

        @Override
        public Optional<GoldenCandidate> find(UUID id) {
            return Optional.ofNullable(candidates.get(id));
        }

        @Override
        public boolean eventExists(String idempotencyKey) {
            return eventKeys.contains(idempotencyKey);
        }

        @Override
        public boolean casTransition(GoldenCandidate next, GoldenCandidateState expectedState,
                                     GoldenReviewEvent event) {
            GoldenCandidate current = candidates.get(next.id());
            if (current == null || current.state() != expectedState
                    || current.revision() != event.expectedRevision()) {
                return false;
            }
            candidates.put(next.id(), next);
            events.add(event);
            eventKeys.add(event.idempotencyKey());
            return true;
        }

        @Override
        public List<GoldenReviewEvent> events(UUID candidateId) {
            return events.stream().filter(e -> e.candidateId().equals(candidateId)).toList();
        }
    }
}

package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.model.GoldenCandidate;
import com.objwww.pr.control.eval.domain.model.GoldenCandidateState;
import com.objwww.pr.control.eval.domain.model.GoldenReviewAction;
import com.objwww.pr.control.eval.domain.model.GoldenReviewEvent;
import com.objwww.pr.control.eval.domain.repository.GoldenCandidateRepository;
import com.objwww.pr.control.eval.domain.statemachine.GoldenCandidateStateMachine;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Golden Candidate 应用服务（M5-03）：双人复核事务编排——发布/拒绝必须双签
 * 且同人不能双签（INV-AM5-2；DB 面由 V22 ck_golden_dual_review 兜底）；
 * 全部迁移经状态机矩阵（无旁路）+ revision CAS + 幂等重放（idempotencyKey）。
 * 事务边界在 PostgresGoldenCandidateRepository（UPDATE 候选 + INSERT 事件同事务）。
 * 装配归 EvalRunnerConfig（M5-06+），本任务不加 @Service（无仓储 bean 面）。
 */
public class GoldenCandidateService {

    /** 复核决定：PUBLISH = 双签发布；REJECT = 双签拒绝 */
    public enum Decision {PUBLISH, REJECT}

    private final GoldenCandidateRepository repository;

    public GoldenCandidateService(GoldenCandidateRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不得为 null");
    }

    /** 提案：DRAFT 候选 + PROPOSED 事件（revision 0） */
    public GoldenCandidate propose(UUID caseVersionId, Map<String, Object> proposedGt,
                                   String reason, String proposedBy) {
        Instant now = Instant.now();
        GoldenCandidate draft = new GoldenCandidate(UUID.randomUUID(), caseVersionId,
                proposedGt, reason, GoldenCandidateState.DRAFT, proposedBy, null, null,
                0, now, now);
        GoldenReviewEvent event = new GoldenReviewEvent(UUID.randomUUID(), draft.id(),
                GoldenReviewAction.PROPOSED, proposedBy, 0,
                "propose-" + draft.id(), Map.of("to", GoldenCandidateState.DRAFT.name()), now);
        repository.insertDraft(draft, event);
        return draft;
    }

    /** 提交评审：DRAFT→REVIEW */
    public void submit(UUID id, String actor, long expectedRevision, String idempotencyKey) {
        transition(id, GoldenCandidateState.REVIEW, GoldenReviewAction.SUBMITTED,
                actor, null, null, expectedRevision, idempotencyKey);
    }

    /** 双签复核：REVIEW→PUBLISHED/REJECTED；同人双签在进入仓储前拒绝（INV-AM5-2） */
    public void decide(UUID id, Decision decision, String reviewerA, String reviewerB,
                       long expectedRevision, String idempotencyKey) {
        Objects.requireNonNull(decision, "decision 不得为 null");
        requireText(reviewerA, "reviewerA");
        requireText(reviewerB, "reviewerB");
        if (reviewerA.equals(reviewerB)) {
            throw new IllegalArgumentException(
                    "INV-AM5-2 同人不能双签: reviewerA=reviewerB=" + reviewerA);
        }
        GoldenCandidateState to = decision == Decision.PUBLISH
                ? GoldenCandidateState.PUBLISHED
                : GoldenCandidateState.REJECTED;
        GoldenReviewAction action = decision == Decision.PUBLISH
                ? GoldenReviewAction.PUBLISHED
                : GoldenReviewAction.REJECTED;
        transition(id, to, action, reviewerA, reviewerA, reviewerB, expectedRevision, idempotencyKey);
    }

    /** 撤回：DRAFT|REVIEW→WITHDRAWN（提案人单方动作，无双签要求） */
    public void withdraw(UUID id, String actor, long expectedRevision, String idempotencyKey) {
        transition(id, GoldenCandidateState.WITHDRAWN, GoldenReviewAction.WITHDRAWN,
                actor, null, null, expectedRevision, idempotencyKey);
    }

    private void transition(UUID id, GoldenCandidateState to, GoldenReviewAction action,
                            String actor, String reviewerA, String reviewerB,
                            long expectedRevision, String idempotencyKey) {
        GoldenCandidate current = repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("golden candidate 不存在: " + id));
        // 幂等重放先于状态机门：同 key 重试发生在状态已迁移之后，是正常重试而非旁路；
        // 新操作（新 key）撞已迁移状态仍被矩阵拒绝
        if (repository.eventExists(idempotencyKey)) {
            return;
        }
        GoldenCandidateStateMachine.requireTransition(current.state(), to);
        Instant now = Instant.now();
        GoldenCandidate next = new GoldenCandidate(current.id(), current.caseVersionId(),
                current.proposedGt(), current.reason(), to, current.proposedBy(),
                reviewerA != null ? reviewerA : current.reviewerA(),
                reviewerB != null ? reviewerB : current.reviewerB(),
                current.revision() + 1, current.createdAt(), now);
        GoldenReviewEvent event = new GoldenReviewEvent(UUID.randomUUID(), id, action,
                actor, expectedRevision, idempotencyKey,
                Map.of("to", to.name(), "from", current.state().name()), now);
        if (!repository.casTransition(next, current.state(), event)) {
            throw new IllegalStateException("golden candidate CAS 失败（旁路或并发修改）: id="
                    + id + " expected state=" + current.state() + " revision=" + expectedRevision);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }
}

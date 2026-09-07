package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.control.ops.domain.model.CaseAction;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;
import com.objwww.pr.control.ops.domain.statemachine.OperatorCaseStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * OperatorCase 应用服务（M5-11；无 HTTP 面——API 归 M5-12）。
 *
 * <p>三条铁律：
 * <ul>
 *   <li><b>幂等合并</b>：openOrMerge 以 (tenant,fingerprint) FOR UPDATE 串行化——
 *       再发生 = 既有单 revision+1 + activity 追加，永不新建单（E-17 keep db.py 同构）；</li>
 *   <li><b>命令 CAS</b>：claim/ack/resolve/assign 以 expected-revision 条件更新收口，
 *       败者 {@link CaseRevisionConflictException} 零副作用（并发认领恰一人成功）；</li>
 *   <li><b>结案不改历史</b>：本服务零 Run 依赖，RESOLVED 吸收态仅 MERGE 可落
 *       （复发只记 activity，不复活结案、不改写 Run 结论）。</li>
 * </ul>
 *
 * <p>幂等重放：命令带 idempotency-key，命中既有审计行 (action,key) 即零副作用返回
 * 当前投影（SLA 升级恰一次的重放锚同面）。转换规则名（T*）随结构化日志落账。
 */
public class OperatorCaseService {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorCaseService.class);

    private final OperatorCaseRepository repository;
    private final Supplier<Instant> clock;

    public OperatorCaseService(OperatorCaseRepository repository, Supplier<Instant> clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 开单/合并结果投影 */
    public record MergeOutcome(UUID caseId, boolean created, long revision) {
    }

    // -------------------------------------------------------------- 幂等合并（单侧入口）

    public MergeOutcome openOrMerge(CaseDraft draft) {
        Instant now = clock.get();
        OperatorCase existing = repository.lockByTenantAndFingerprint(
                draft.tenant(), draft.fingerprint()).orElse(null);
        if (existing == null) {
            OperatorCase created = create(draft, now);
            return new MergeOutcome(created.id(), true, created.revision());
        }
        if (replayed(existing, CaseAction.MERGE, draft.idempotencyKey())) {
            return new MergeOutcome(existing.id(), false, existing.revision());
        }
        long nextRevision = existing.revision() + 1;
        OperatorCaseStateMachine.Transition transition =
                OperatorCaseStateMachine.apply(existing.status(), CaseAction.MERGE);
        OperatorCase merged = existing.applyTransition(
                transition.to(), existing.owner(), existing.resolution(),
                new OperatorCase.Activity(now, "system", recurrenceText(draft)),
                new OperatorCase.Audit(now, "system", "CASE_MERGED", nextRevision,
                        draft.idempotencyKey()),
                now);
        cas(merged, existing.revision());
        logRule(transition, merged.id(), nextRevision);
        return new MergeOutcome(merged.id(), false, merged.revision());
    }

    // -------------------------------------------------------------- 命令面

    public OperatorCase claim(UUID caseId, long expectedRevision, String actor, String idempotencyKey) {
        return command(caseId, expectedRevision, actor, idempotencyKey, CaseAction.CLAIM,
                (c, t, nextRevision, now) -> c.applyTransition(t.to(), actor, c.resolution(),
                        activity(now, actor, "认领 Case，owner=" + actor),
                        audit(now, actor, "CLAIM", nextRevision, idempotencyKey), now));
    }

    public OperatorCase ack(UUID caseId, long expectedRevision, String actor, String idempotencyKey) {
        return command(caseId, expectedRevision, actor, idempotencyKey, CaseAction.ACK,
                (c, t, nextRevision, now) -> c.applyTransition(t.to(), c.owner(), c.resolution(),
                        activity(now, actor, "确认 Case"),
                        audit(now, actor, "ACK", nextRevision, idempotencyKey), now));
    }

    public OperatorCase resolve(UUID caseId, long expectedRevision, String reasonCode,
                                String note, String actor, String idempotencyKey) {
        if (reasonCode == null || reasonCode.isBlank() || note == null || note.isBlank()) {
            throw new IllegalArgumentException("resolve 必填结构化 reason(code,note)");
        }
        return command(caseId, expectedRevision, actor, idempotencyKey, CaseAction.RESOLVE,
                (c, t, nextRevision, now) -> c.applyTransition(t.to(), c.owner(),
                        new OperatorCase.Resolution(reasonCode, note, now),
                        activity(now, actor, "结案 reason=" + reasonCode),
                        audit(now, actor, "RESOLVE", nextRevision, idempotencyKey), now));
    }

    public OperatorCase assign(UUID caseId, long expectedRevision, String toOwner,
                               String actor, String idempotencyKey) {
        Objects.requireNonNull(toOwner, "toOwner");
        return command(caseId, expectedRevision, actor, idempotencyKey, CaseAction.ASSIGN,
                (c, t, nextRevision, now) -> c.applyTransition(t.to(), toOwner, c.resolution(),
                        activity(now, actor, "转派 owner=" + toOwner),
                        audit(now, actor, "ASSIGN", nextRevision, idempotencyKey), now));
    }

    /** SLA 升级：不改状态，审计 SLA_ESCALATED + revision+1；outbox 关联列（V26 case_id）
     *  为 AM7 IN_APP 渠道预留，本任务不写 notify_outbox（C-16③） */
    public OperatorCase escalate(UUID caseId, String actor, String idempotencyKey) {
        OperatorCase current = repository.findById(caseId).orElseThrow(
                () -> new CaseNotFoundException(caseId));
        if (replayed(current, CaseAction.ESCALATE, idempotencyKey)) {
            return current;
        }
        return applyCommand(current, current.revision(), actor, idempotencyKey, CaseAction.ESCALATE,
                (c, t, nextRevision, now) -> c.applyTransition(t.to(), c.owner(), c.resolution(),
                        activity(now, actor, "SLA 升级（已逾期）"),
                        audit(now, actor, "SLA_ESCALATED", nextRevision, idempotencyKey), now));
    }

    // -------------------------------------------------------------- 内部

    private interface CommandBody {
        OperatorCase apply(OperatorCase current, OperatorCaseStateMachine.Transition transition,
                           long nextRevision, Instant now);
    }

    private OperatorCase command(UUID caseId, long expectedRevision, String actor,
                                 String idempotencyKey, CaseAction action, CommandBody body) {
        OperatorCase current = repository.findById(caseId).orElseThrow(
                () -> new CaseNotFoundException(caseId));
        if (replayed(current, action, idempotencyKey)) {
            return current;
        }
        return applyCommand(current, expectedRevision, actor, idempotencyKey, action, body);
    }

    private OperatorCase applyCommand(OperatorCase current, long expectedRevision, String actor,
                                      String idempotencyKey, CaseAction action, CommandBody body) {
        long nextRevision = current.revision() + 1;
        OperatorCaseStateMachine.Transition transition =
                OperatorCaseStateMachine.apply(current.status(), action);
        Instant now = clock.get();
        OperatorCase next = body.apply(current, transition, nextRevision, now);
        if (!repository.update(next, expectedRevision)) {
            throw new CaseRevisionConflictException(current.id(), expectedRevision);
        }
        logRule(transition, current.id(), nextRevision);
        return next;
    }

    private OperatorCase create(CaseDraft draft, Instant now) {
        OperatorCaseStateMachine.Transition transition =
                OperatorCaseStateMachine.apply(null, CaseAction.CREATE);
        long revision = 1;
        OperatorCase created = new OperatorCase(UUID.randomUUID(), draft.tenant(),
                draft.fingerprint(), draft.subject(), draft.priority(), draft.reasonCode(),
                transition.to(), null, draft.runId(), draft.taskId(), draft.incidentType(),
                draft.snapshotDigest(), draft.observedGeneration(), draft.evidenceRefs(),
                List.of(new OperatorCase.Activity(now, "system",
                        "Case 创建，来源 run#" + draft.runId() + "/task#" + draft.taskId()
                                + "，generation=" + draft.observedGeneration())),
                List.of(new OperatorCase.Audit(now, "system", "CASE_CREATED", revision,
                        draft.idempotencyKey())),
                null, now, draft.ackDue(), draft.resolveDue(), revision, now, now);
        repository.insert(created);
        logRule(transition, created.id(), revision);
        return created;
    }

    /** 幂等重放锚：审计行含同 (action,key) 即已执行过，零副作用返回当前投影。
     *  动作 → 审计名映射：MERGE 落 CASE_MERGED、ESCALATE 落 SLA_ESCALATED，其余同名 */
    private boolean replayed(OperatorCase current, CaseAction action, String idempotencyKey) {
        String auditAction = switch (action) {
            case MERGE -> "CASE_MERGED";
            case ESCALATE -> "SLA_ESCALATED";
            default -> action.name();
        };
        return current.audits().stream().anyMatch(a ->
                a.action().equals(auditAction) && a.key().equals(idempotencyKey));
    }

    private void cas(OperatorCase next, long expectedRevision) {
        if (!repository.update(next, expectedRevision)) {
            throw new CaseRevisionConflictException(next.id(), expectedRevision);
        }
    }

    private String recurrenceText(CaseDraft draft) {
        return "同 fingerprint 复发聚合，来源 run#" + draft.runId() + "/task#" + draft.taskId();
    }

    private OperatorCase.Activity activity(Instant now, String actor, String text) {
        return new OperatorCase.Activity(now, actor, text);
    }

    private OperatorCase.Audit audit(Instant now, String actor, String action,
                                     long revision, String key) {
        return new OperatorCase.Audit(now, actor, action, revision, key);
    }

    /** 转换规则名落结构化日志（ISA 18.2 式可审计面；字段仅标识符，无内容） */
    private void logRule(OperatorCaseStateMachine.Transition transition, UUID caseId, long revision) {
        StructuredLog.event(LOG, "operator_case_transition", Map.of(
                "caseId", caseId,
                "rule", transition.rule(),
                "to", transition.to().name(),
                "revision", revision));
    }
}

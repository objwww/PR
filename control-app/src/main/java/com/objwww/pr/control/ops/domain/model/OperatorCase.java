package com.objwww.pr.control.ops.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * OperatorCase 聚合根（M5-11；V26 operator_case 行投影；方案 §3.1 ops/domain 划面）。
 *
 * <p>快照冻结（P4 annot）：snapshot_digest/observed_generation 固定创建时值——
 * 迟到证据进入新快照，不改写旧裁决上下文；结案不回写 Run 结论（本聚合零 Run 依赖，
 * 结构上不可能改写）。evidence_refs N≥1（每个 Case 至少一条可核验来源引用）。
 *
 * <p>activities/audits 内嵌 jsonb 投影（C-16①：P4 mock audits[] 形状——
 * {time,actor,action,revision,key}，key 兼作命令幂等重放锚）。
 */
public record OperatorCase(UUID id,
                           String tenant,
                           String fingerprint,
                           String subject,
                           String priority,
                           String reasonCode,
                           CaseStatus status,
                           String owner,
                           UUID runId,
                           String taskId,
                           String incidentType,
                           Digest snapshotDigest,
                           int observedGeneration,
                           List<String> evidenceRefs,
                           List<Activity> activities,
                           List<Audit> audits,
                           Resolution resolution,
                           Instant firstSeen,
                           Instant ackDue,
                           Instant resolveDue,
                           long revision,
                           Instant createdAt,
                           Instant updatedAt) {

    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2");

    public OperatorCase {
        Objects.requireNonNull(id, "id 不得为 null");
        requireNonBlank(tenant, "tenant");
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 64) {
            throw new IllegalArgumentException("fingerprint 必为 1..64 字符: " + fingerprint);
        }
        requireNonBlank(subject, "subject");
        requireNonBlank(reasonCode, "reasonCode");
        if (priority == null || !PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("priority 限于 P0/P1/P2: " + priority);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidenceRefs, "evidenceRefs");
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("evidence_refs N≥1（每个 Case 至少一条可核验来源）");
        }
        if (observedGeneration < 0) {
            throw new IllegalArgumentException("observedGeneration 不得为负");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 从 1 起");
        }
        evidenceRefs = List.copyOf(evidenceRefs);
        activities = activities == null ? List.of() : List.copyOf(activities);
        audits = audits == null ? List.of() : List.copyOf(audits);
    }

    /**
     * 命令应用 wither：状态机 Transition 的目标态 + 可选 owner/resolution 落位，
     * activity/audit 成对追加（revision 随审计行走）。调用方保证已过状态机门。
     */
    public OperatorCase applyTransition(CaseStatus to, String newOwner, Resolution newResolution,
                                        Activity activity, Audit audit, Instant now) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(now, "now");
        return new OperatorCase(id, tenant, fingerprint, subject, priority, reasonCode,
                to, newOwner, runId, taskId, incidentType, snapshotDigest, observedGeneration,
                evidenceRefs,
                concat(activities, activity), concat(audits, audit),
                newResolution, firstSeen, ackDue, resolveDue, audit.revision(), createdAt, now);
    }

    private static <T> List<T> concat(List<T> list, T appended) {
        var grown = new java.util.ArrayList<T>(list.size() + 1);
        grown.addAll(list);
        grown.add(appended);
        return List.copyOf(grown);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }

    /** 活动流水行（前端 activities[] 投影） */
    public record Activity(Instant at, String actor, String text) {
        public Activity {
            Objects.requireNonNull(at, "at");
            requireNonBlank(actor, "actor");
            requireNonBlank(text, "text");
        }
    }

    /** 审计行（前端 audits[] 投影；key = 命令 idempotency-key，兼重放锚） */
    public record Audit(Instant at, String actor, String action, long revision, String key) {
        public Audit {
            Objects.requireNonNull(at, "at");
            requireNonBlank(actor, "actor");
            requireNonBlank(action, "action");
            if (revision < 1) {
                throw new IllegalArgumentException("audit revision 从 1 起");
            }
        }
    }

    /** 结构化结案原因（resolve 必填：code + note，方案 §M5-12 契约同源） */
    public record Resolution(String code, String note, Instant at) {
        public Resolution {
            requireNonBlank(code, "code");
            requireNonBlank(note, "note");
            Objects.requireNonNull(at, "at");
        }
    }
}

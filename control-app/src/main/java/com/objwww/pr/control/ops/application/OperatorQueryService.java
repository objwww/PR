package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimLifecycle;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.ops.domain.model.CaseStatus;
import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Operator Case 只读投影（M5-12；字段级契约 = mocks/cases.js + CasesView.vue
 * 实际消费面）。详情工作区 evidence/claims 经 EvidenceRepository/ClaimStore 投影
 * 白名单摘要面（A1；禁回 canonicalPayload 正文）；结案只读，无写路径。
 */
public class OperatorQueryService {

    private static final Map<String, Integer> PRIORITY_RANK = Map.of("P0", 0, "P1", 1, "P2", 2);

    private final OperatorCaseRepository repository;
    private final Supplier<Instant> clock;
    private final EvidenceRepository evidenceRepository;
    private final ClaimStore claimStore;

    public OperatorQueryService(OperatorCaseRepository repository, Supplier<Instant> clock,
                                EvidenceRepository evidenceRepository, ClaimStore claimStore) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore");
    }

    /**
     * 过渡兼容构造（A1 §六收口前 PersistenceConfig 旧 2 参装配用；收口切 4 参后删除本构造
     * 与 UNWIRED_* 两个空读 shim）。空读 = 接通前行为（evidence/claims 恒空列表），不编造数据。
     */
    public OperatorQueryService(OperatorCaseRepository repository, Supplier<Instant> clock) {
        this(repository, clock, UNWIRED_EVIDENCE, UNWIRED_CLAIMS);
    }

    private static final EvidenceRepository UNWIRED_EVIDENCE = new EvidenceRepository() {
        @Override
        public void insert(EvidenceEnvelope envelope) {
            throw new UnsupportedOperationException("unwired read-face shim");
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
            return Optional.empty();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return List.of();
        }
    };

    private static final ClaimStore UNWIRED_CLAIMS = new ClaimStore() {
        @Override
        public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
            throw new UnsupportedOperationException("unwired read-face shim");
        }

        @Override
        public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
            throw new UnsupportedOperationException("unwired read-face shim");
        }

        @Override
        public List<ClaimRow> findByRunId(UUID runId) {
            return List.of();
        }
    };

    /** tab 计数（view 语义：mine/all/unassigned/overdue 均排除 RESOLVED；notifyUnread 待 AM7） */
    public Map<String, Object> summary(String actor) {
        Instant now = clock.get();
        List<OperatorCase> open = repository.findAll().stream()
                .filter(c -> c.status() != CaseStatus.RESOLVED)
                .toList();
        Map<String, Object> tabs = new LinkedHashMap<>();
        tabs.put("mine", open.stream().filter(c -> actor.equals(c.owner())).count());
        tabs.put("all", (long) open.size());
        tabs.put("unassigned", open.stream().filter(c -> c.owner() == null).count());
        tabs.put("overdue", open.stream().filter(c -> overdue(c, now)).count());
        tabs.put("notifyUnread", 0L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updatedAt", now.toString());
        body.put("tabs", tabs);
        return body;
    }

    /**
     * 列表：bucket ∈ mine/all/unassigned/overdue（缺省 all，view 兼容）+ status/
     * priority/reasonCode 等值过滤；SLA 风险排序（逾期优先 → 优先级 → resolve_due）。
     */
    public List<Map<String, Object>> list(String bucket, String actor, String status,
                                          String priority, String reasonCode) {
        Instant now = clock.get();
        Comparator<OperatorCase> slaRisk = Comparator
                .comparing((OperatorCase c) -> !overdue(c, now))
                .thenComparing(c -> PRIORITY_RANK.getOrDefault(c.priority(), 9))
                .thenComparing(c -> c.resolveDue() == null ? Instant.MAX : c.resolveDue())
                .thenComparing(OperatorCase::firstSeen);
        return repository.findAll().stream()
                .filter(c -> matchesBucket(c, bucket, actor, now))
                .filter(c -> status == null || c.status().name().equals(status))
                .filter(c -> priority == null || c.priority().equals(priority))
                .filter(c -> reasonCode == null || c.reasonCode().equals(reasonCode))
                .sorted(slaRisk)
                .map(this::projection)
                .toList();
    }

    /** 详情工作区投影；未知 id → empty（controller 404 面） */
    public Optional<Map<String, Object>> detail(UUID id) {
        return repository.findById(id).map(this::workspace);
    }

    /** 列表单项投影（命令响应体 case 字段共用） */
    public Optional<Map<String, Object>> listItem(UUID id) {
        return repository.findById(id).map(this::projection);
    }

    // ------------------------------------------------------------------ 投影

    /** 列表行投影（mock cases[] 字段级） */
    public Map<String, Object> projection(OperatorCase c) {
        Instant now = clock.get();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.id().toString());
        map.put("subject", c.subject());
        map.put("priority", c.priority());
        map.put("status", c.status().name());
        map.put("owner", c.owner());
        map.put("reasonCode", c.reasonCode());
        map.put("incidentType", c.incidentType());
        map.put("runId", c.runId() == null ? null : c.runId().toString());
        map.put("taskId", c.taskId());
        map.put("firstSeen", c.firstSeen().toString());
        map.put("ackDue", c.ackDue() == null ? null : c.ackDue().toString());
        map.put("resolveDue", c.resolveDue() == null ? null : c.resolveDue().toString());
        map.put("slaLabel", slaLabel(c, now));
        map.put("ackOverdue", ackOverdue(c, now));
        map.put("overdue", overdue(c, now));
        map.put("evidenceCount", c.evidenceRefs().size());
        map.put("revision", c.revision());
        return map;
    }

    /** 详情工作区投影（mock detail 形状：activities 字符串行 + audits {time,...} 键） */
    private Map<String, Object> workspace(OperatorCase c) {
        Map<String, Object> map = new LinkedHashMap<>(projection(c));
        map.put("headline", c.subject());
        map.put("summary", "Case（reason_code=" + c.reasonCode() + "），来源 run#" + c.runId()
                + "/task#" + c.taskId() + "。处置动作均要求 expected_revision + idempotency_key。");
        map.put("sourceRefs", Map.of("count", c.evidenceRefs().size(),
                "text", "Evidence×" + c.evidenceRefs().size()));
        map.put("snapshot", c.snapshotDigest() == null ? null : c.snapshotDigest().hex());
        map.put("generation", c.observedGeneration());
        List<ClaimStore.ClaimRow> claims = c.runId() == null ? List.of()
                : claimStore.findByRunId(c.runId()).stream()
                        .sorted(Comparator.comparing(ClaimStore.ClaimRow::claimKey,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();
        map.put("evidence", c.runId() == null ? List.of()
                : evidenceRepository.findByRunId(c.runId()).stream()
                        .map(OperatorQueryService::evidenceRow)
                        .toList());
        map.put("claims", claims.stream().map(OperatorQueryService::claimRow).toList());
        String conflictKeys = claims.stream()
                .filter(r -> r.lifecycle() == ClaimLifecycle.ACTIVE
                        && r.evidenceBasis() == EvidenceBasis.MULTI_SOURCE_CONFLICT)
                .map(ClaimStore.ClaimRow::claimKey)
                .collect(Collectors.joining(","));
        map.put("conflictNote", conflictKeys.isEmpty() ? null : conflictKeys);
        map.put("activities", c.activities().stream()
                .map(a -> a.at() + " " + a.actor() + " ｜ " + a.text())
                .toList());
        List<Map<String, Object>> audits = new ArrayList<>();
        for (OperatorCase.Audit a : c.audits()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", a.at().toString());
            row.put("actor", a.actor());
            row.put("action", a.action());
            row.put("revision", a.revision());
            row.put("key", a.key());
            audits.add(row);
        }
        map.put("audits", audits);
        map.put("evidenceRefs", c.evidenceRefs());
        map.put("resolution", c.resolution() == null ? null : Map.of(
                "code", c.resolution().code(),
                "note", c.resolution().note(),
                "at", c.resolution().at().toString()));
        return map;
    }

    /** evidence 白名单摘要行（A1 映射表写死；summary 无源显 null，不回 canonicalPayload） */
    private static Map<String, Object> evidenceRow(EvidenceEnvelope e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.evidenceId().toString());
        row.put("type", e.evidenceType());
        row.put("source", e.source());
        row.put("window", e.timeStart() == null || e.timeEnd() == null ? null
                : e.timeStart() + " ~ " + e.timeEnd());
        row.put("verify", "VERIFIED");
        row.put("summary", null);
        if (e.taskId() != null) {
            row.put("taskId", e.taskId().toString());
        }
        return row;
    }

    /** claims 白名单摘要行（A1 映射表写死） */
    private static Map<String, Object> claimRow(ClaimStore.ClaimRow r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.id().toString());
        row.put("text", r.reason());
        row.put("verdict", r.status().name());
        row.put("evidenceRefs", r.evidenceRefs());
        return row;
    }

    // ------------------------------------------------------------------ SLA 面

    private boolean matchesBucket(OperatorCase c, String bucket, String actor, Instant now) {
        if (bucket == null || bucket.isBlank() || "all".equals(bucket)) {
            return c.status() != CaseStatus.RESOLVED;
        }
        return switch (bucket) {
            case "mine" -> actor.equals(c.owner()) && c.status() != CaseStatus.RESOLVED;
            case "unassigned" -> c.owner() == null && c.status() != CaseStatus.RESOLVED;
            case "overdue" -> overdue(c, now) && c.status() != CaseStatus.RESOLVED;
            default -> false;
        };
    }

    private boolean overdue(OperatorCase c, Instant now) {
        return c.status() != CaseStatus.RESOLVED && c.resolveDue() != null
                && now.isAfter(c.resolveDue());
    }

    private String slaLabel(OperatorCase c, Instant now) {
        if (overdue(c, now)) {
            return "已逾期";
        }
        if (c.resolveDue() == null) {
            return null;
        }
        long minutes = Duration.between(now, c.resolveDue()).toMinutes();
        return minutes < 90 ? minutes + "m" : (minutes / 60) + "h";
    }

    private String ackOverdue(OperatorCase c, Instant now) {
        if (c.status() != CaseStatus.OPEN || c.ackDue() == null || !now.isAfter(c.ackDue())) {
            return null;
        }
        return "ACK 超时 " + Duration.between(c.ackDue(), now).toMinutes() + "m";
    }
}

package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.eval.application.FinalReportSelector;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 调查动作价值分析（OP-03，方案 §4）：Run 终态后可重入执行——按逻辑动作
 * （taskId+actionDigest，内容寻址）聚合物理尝试，关联证据与最终报告引用，
 * 落版本化派生台账（不改原始账本）。
 *
 * <p>确定性规则（零 LLM，局限随 confidenceKind 声明）：
 * <ul>
 *   <li>新观察按 (evidenceType, canonicalPayload) 全 run 去重（FO11——证据 UUID
 *       不同≠新观察；FO12 频次/时间窗变化会改 canonical payload，即新观察）；</li>
 *   <li>分类优先级：SOURCE_FAILED（全败）&gt; UNDETERMINED（无终态知识）&gt;
 *       NO_DATA（成功但无可用证据）&gt; DUPLICATE_SAME_SNAPSHOT（观察集全已见）&gt;
 *       CONFIRMS_OR_REFUTES（最终报告引用）&gt; NEW_OBSERVATION；</li>
 *   <li>FO14：同逻辑键多次物理尝试聚为一行（physical_attempts 计数）；</li>
 *   <li>FO15：同版本同快照重入幂等；迟到证据/报告变化 → 新快照摘要 → 重算新行。</li>
 * </ul>
 * 分类是描述性归因非因果效益；"价值"结论须配对实验佐证（§4.1）。
 */
public class ActionAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(ActionAssessmentService.class);

    public static final String ASSESSOR_VERSION = "action-assessor.v1";
    public static final String CONFIDENCE_KIND = "deterministic-rules.v1";

    private final RcaTaskRepository tasks;
    private final RcaToolInvocationLedger ledger;
    private final EvidenceRepository evidenceRepository;
    private final RcaReportRepository reports;
    private final com.objwww.pr.control.ops.domain.repository.ActionAssessmentPort assessments;
    private final Clock clock;

    public ActionAssessmentService(RcaTaskRepository tasks,
            RcaToolInvocationLedger ledger, EvidenceRepository evidenceRepository,
            RcaReportRepository reports,
            com.objwww.pr.control.ops.domain.repository.ActionAssessmentPort assessments,
            Clock clock) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 一次分析结果：rows=本快照版本的分析行；replayed=同版本同快照已算过（幂等命中） */
    public record AssessmentOutcome(String evidenceSnapshotDigest,
            List<com.objwww.pr.control.ops.domain.model.ActionAssessment> rows,
            boolean replayed) {
    }

    public AssessmentOutcome assess(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        // 账本行：任务维度恢复读拼全 run（含 FAILED/UNKNOWN——失败源可见性）；
        // InvocationRecovery 投影无 taskId 列，任务身份由查询参数侧登记
        Map<UUID, List<InvocationRecovery>> byTask = new LinkedHashMap<>();
        for (RcaTask task : tasks.findByRunId(runId)) {
            byTask.put(task.id(), ledger.findRecoveryByTask(runId, task.id()));
        }
        List<InvocationRecovery> ledgerRows = byTask.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingLong(InvocationRecovery::callSeq)
                        .thenComparing(InvocationRecovery::operationId))
                .toList();
        List<EvidenceEnvelope> evidence = evidenceRepository.findByRunId(runId);
        Map<UUID, EvidenceEnvelope> evidenceById = new LinkedHashMap<>();
        for (EvidenceEnvelope row : evidence) {
            evidenceById.put(row.evidenceId(), row);
        }
        // 最终报告（引用面）：已验证报告取最晚；无报告 → 空引用集（不编造引用）
        RcaReport finalReport = new FinalReportSelector()
                .select(reports.findByRunId(runId)).orElse(null);
        String packageJson = finalReport == null ? "" : finalReport.packageJson();
        String reportPart = finalReport == null ? "none"
                : Digest.sha256Of(finalReport.packageJson()).value();
        String snapshotDigest = snapshotDigestOf(evidence, reportPart);

        // FO11/FO12：全局 callSeq 序扫观察面，(evidenceType, canonicalPayload) 首见即新观察
        Set<String> seen = new HashSet<>();
        Set<UUID> firstObservationOps = new HashSet<>();
        for (InvocationRecovery row : ledgerRows) {
            if (row.state() != ToolInvocationState.SUCCESS || row.resultRef() == null) {
                continue;
            }
            EvidenceEnvelope item = evidenceById.get(row.resultRef());
            if (item == null) {
                continue;
            }
            String observationKey = item.evidenceType() + "|" + item.canonicalPayload();
            if (seen.add(observationKey)) {
                firstObservationOps.add(row.operationId());
            }
        }

        // 逻辑动作分组（FO14：物理尝试聚合）——actionDigest 内容寻址（含工具+参数）
        Map<String, UUID> groupTask = new LinkedHashMap<>();
        Map<String, List<InvocationRecovery>> groups = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<InvocationRecovery>> taskRows : byTask.entrySet()) {
            for (InvocationRecovery row : taskRows.getValue()) {
                String key = taskRows.getKey() + "#" + row.actionDigest();
                groupTask.putIfAbsent(key, taskRows.getKey());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        boolean anyReplayed = false;
        List<com.objwww.pr.control.ops.domain.model.ActionAssessment> out = new ArrayList<>();
        for (Map.Entry<String, List<InvocationRecovery>> group : groups.entrySet()) {
            List<InvocationRecovery> rows = group.getValue();
            UUID taskId = groupTask.get(group.getKey());
            long successes = rows.stream()
                    .filter(r -> r.state() == ToolInvocationState.SUCCESS).count();
            long failures = rows.stream()
                    .filter(r -> r.state() == ToolInvocationState.FAILED).count();
            List<EvidenceEnvelope> resolved = rows.stream()
                    .filter(r -> r.state() == ToolInvocationState.SUCCESS
                            && r.resultRef() != null && evidenceById.containsKey(r.resultRef()))
                    .map(r -> evidenceById.get(r.resultRef())).toList();
            int citationCount = (int) resolved.stream()
                    .filter(e -> !packageJson.isEmpty()
                            && packageJson.contains(e.evidenceId().toString()))
                    .count();
            boolean anyNewObservation = rows.stream()
                    .anyMatch(r -> firstObservationOps.contains(r.operationId()));

            String classification;
            if (successes == 0 && failures > 0) {
                classification = com.objwww.pr.control.ops.domain.model.ActionAssessment.SOURCE_FAILED;
            } else if (successes == 0) {
                classification = com.objwww.pr.control.ops.domain.model.ActionAssessment.UNDETERMINED;
            } else if (resolved.isEmpty()) {
                classification = com.objwww.pr.control.ops.domain.model.ActionAssessment.NO_DATA;
            } else if (!anyNewObservation) {
                classification = com.objwww.pr.control.ops.domain.model
                        .ActionAssessment.DUPLICATE_SAME_SNAPSHOT;
            } else if (citationCount > 0) {
                classification = com.objwww.pr.control.ops.domain.model
                        .ActionAssessment.CONFIRMS_OR_REFUTES;
            } else {
                classification = com.objwww.pr.control.ops.domain.model
                        .ActionAssessment.NEW_OBSERVATION;
            }
            com.objwww.pr.control.ops.domain.model.ActionAssessment candidate =
                    new com.objwww.pr.control.ops.domain.model.ActionAssessment(
                            UUID.randomUUID(), runId, taskId, group.getKey(),
                            ASSESSOR_VERSION, snapshotDigest, rows.size(),
                            com.objwww.pr.control.ops.domain.model.ActionAssessment.NEW_OBSERVATION
                                    .equals(classification) ? 1 : 0,
                            List.of(), citationCount, classification, CONFIDENCE_KIND,
                            clock.instant());
            com.objwww.pr.control.ops.domain.model.ActionAssessment stored =
                    assessments.insertIfAbsent(candidate);
            anyReplayed |= !stored.id().equals(candidate.id());
            out.add(stored);
        }
        log.info("动作分析完成 run={} snapshot={} actions={} replayed={}",
                runId, snapshotDigest, out.size(), anyReplayed);
        return new AssessmentOutcome(snapshotDigest, out, anyReplayed);
    }

    /**
     * 源快照摘要：排序后的 (evidenceId, payloadDigest) 全集 + 最终报告 digest——
     * 迟到证据或报告变化都会改变摘要，触发新版本行（同版本旧快照行保留）。
     */
    private static String snapshotDigestOf(List<EvidenceEnvelope> evidence,
            String reportPart) {
        StringBuilder sb = new StringBuilder(reportPart);
        evidence.stream()
                .sorted(Comparator.comparing(e -> e.evidenceId().toString()))
                .forEach(e -> sb.append('|')
                        .append(e.evidenceId()).append(':').append(e.payloadDigest()));
        return Digest.sha256Of(sb.toString()).value();
    }

    public List<com.objwww.pr.control.ops.domain.model.ActionAssessment> byRun(UUID runId) {
        return assessments.findByRun(runId);
    }
}

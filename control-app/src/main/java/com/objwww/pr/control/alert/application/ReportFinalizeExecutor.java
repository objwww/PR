package com.objwww.pr.control.alert.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.ExecutionStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.domain.port.ArtifactStore;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SR §4.3 REPORT_FINALIZE 恢复执行器：只组装/验证<b>既有持久材料</b>——禁止隐式
 * LLM 调用、禁止重新查现场。材料 = rca_investigation_result 终态行（验证通过 +
 * package_json，由 RcaWorker 收尾事务前的材料预提交落档）+ CAS raw 原文。
 *
 * <p>重放为 {@link RcaTaskExecutor.AttemptArtifact} 走同一 finishTask 收尾事务
 * （报告 + 发布赢家 CAS + outbox 原子链）。幂等对账：已有报告 = 空跑收口
 * （不产第二份报告/发布/outbox）；材料不全 = 终态失败 MATERIALS_INCOMPLETE
 * （不凭推测补跑调查——人工接管）。发布准入的影子负向门在 archiveArtifact，
 * 本执行器不做身份判断（恢复面只对生产孤立 Run 铸造）。
 */
public class ReportFinalizeExecutor implements RcaTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReportFinalizeExecutor.class);

    /** 材料不完整终态失败类（task DEAD → run FAILED，人工接管） */
    public static final String ERROR_CLASS = "MATERIALS_INCOMPLETE";

    private final RcaReportRepository reports;
    private final InvestigationResultRepository investigationResults;
    private final ArtifactStore artifacts;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportFinalizeExecutor(RcaReportRepository reports,
                                  InvestigationResultRepository investigationResults,
                                  ArtifactStore artifacts) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.investigationResults = Objects.requireNonNull(investigationResults,
                "investigationResults");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @Override
    public ExecutionResult execute(RcaTask task, RcaRun run, Incident incident,
                                   RcaAttempt attempt, Runnable heartbeat) {
        // 幂等对账：已有正式报告（恢复与正常收尾竞争的收敛侧）——不产第二份
        if (!reports.findByRunId(run.id()).isEmpty()) {
            log.info("run {} 已有正式报告，REPORT_FINALIZE 幂等空跑收口", run.id());
            return ExecutionResult.successNoArtifact();
        }
        // 材料选择：最新一条验证通过的终态调查行（含 raw 原文引用）
        Optional<InvestigationResult> materials = investigationResults.findByRunId(run.id())
                .stream()
                .filter(row -> row.finishedAt() != null
                        && row.executionStatus() == ExecutionStatus.SUCCEEDED
                        && row.validationStatus() == ValidationStatus.STRUCTURE_VALIDATED
                        && row.packageJson() != null)
                .max(Comparator.comparing(InvestigationResult::finishedAt));
        if (materials.isEmpty()) {
            return ExecutionResult.terminal(ERROR_CLASS,
                    "无可信持久材料（验证通过的终态调查行缺失），不重跑调查");
        }
        InvestigationResult row = materials.get();
        Optional<String> rawText = readRaw(row);
        if (rawText.isEmpty()) {
            return ExecutionResult.terminal(ERROR_CLASS,
                    "raw 原文不可读或 digest 不匹配（ref=" + row.rawArtifactRef() + "），不重跑调查");
        }
        Usage usage = parseUsage(row.usageJson());
        // tool_calls 随原始 attempt 在内存丢失（崩溃缝隙），此处不伪造——报告链完整即可；
        // samplingFingerprint=null 同理：重放收尾不采样（V23 ck 非空指纹键约束拒空对象）
        return ExecutionResult.success(new AttemptArtifact(
                row.schemaVersion(), row.validationStatus(), row.validationErrors(),
                row.packageJson(), rawText.get(), null, List.of(),
                row.rawDigest(), row.payloadDigest(), row.model(),
                usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                usage.missing(), null));
    }

    /** CAS raw 原文读回（ref = "xx/&lt;digest&gt;"；读回复核内容 digest 防错位） */
    private Optional<String> readRaw(InvestigationResult row) {
        String ref = row.rawArtifactRef();
        if (ref == null || ref.indexOf('/') < 0) {
            return Optional.empty();
        }
        try {
            String hex = ref.substring(ref.indexOf('/') + 1);
            byte[] bytes = artifacts.get(new Digest(hex)).orElse(null);
            if (bytes == null) {
                return Optional.empty();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (!Digest.sha256Of(text).value().equals(hex)) {
                log.warn("材料 raw digest 不匹配 ref={}——拒绝使用", ref);
                return Optional.empty();
            }
            return Optional.of(text);
        } catch (RuntimeException e) {
            log.warn("材料 raw 读回失败 ref={}", ref, e);
            return Optional.empty();
        }
    }

    private record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                         boolean missing) {
        static final Usage MISSING = new Usage(null, null, null, true);
    }

    private Usage parseUsage(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return Usage.MISSING;
        }
        try {
            JsonNode node = mapper.readTree(usageJson);
            return new Usage(intOrNull(node, "prompt_tokens"),
                    intOrNull(node, "completion_tokens"), intOrNull(node, "total_tokens"),
                    false);
        } catch (Exception e) {
            return Usage.MISSING;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}

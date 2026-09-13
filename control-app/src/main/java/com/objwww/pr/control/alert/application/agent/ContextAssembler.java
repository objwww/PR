package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.WorkingMemory;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 任务信封装配器（R1/MA-01，R7方案 §19.1 上下文工程契约，am4-envelope.v2）：
 * 把"模型能引用什么"升级为"模型能读到什么"——告警材料/调查目标/预算面/证据有界摘要/
 * 工作记忆/轨迹随信封入模，全部确定性装配（零 LLM、零自由文本生成）。
 *
 * <p>界限（MC09，确定性执行不依赖模型自觉）：
 * <ul>
 *   <li>evidence ≤{@value #EVIDENCE_LIMIT} 条：按时间倒序截取（最新最相关），溢出者
 *       记 omittedRefs——仍可引用（valid_artifact_refs 不裁剪），只是无摘要；</li>
 *   <li>trajectory ≤{@value #TRAJECTORY_LIMIT} 步（保留最近）；working_memory 每槽
 *       ≤{@value #MEMORY_SLOT_LIMIT} 项；单项摘要超界截断并置 truncated=true；</li>
 *   <li>alert.summary ≤{@value #ALERT_SUMMARY_LIMIT}、evidence.summary
 *       ≤{@value #SUMMARY_LIMIT}、memory/trajectory 单项 ≤{@value #ITEM_LIMIT}。</li>
 * </ul>
 *
 * <p>stableDigest = 稳定面（不含 last_error 反馈——反馈每步可变，不是冻结输入）prompt
 * 的 sha256：R5 同签名熔断比较键，成功步经 withStepAdvanced 回填
 * rca_model_call.input_snapshot_digest（G3 接线）。
 *
 * <p>工作记忆（§19.2"记忆不是新证据"——不进 evidence/不进 validRefs）：宿主从
 * 检查点+裁决台账确定性重建为候选（hypotheses=检查点 FINAL 提案史、ruled_out=
 * 裁决拒绝史、open_gaps/counter_evidence_refs 在 PRIMARY_READY 装配时点确定性为
 * 空——未结子任务在 wake 复判后才可能存在，而唤醒成功即已结清）；接 R10 持久面
 * 后经 append 深冻结落 rca_working_memory（同修订重放返回既有行=MC07 恢复读同
 * 快照），信封下发冻结真相，检查点经 memory_id/digest 钉住。
 */
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    static final int EVIDENCE_LIMIT = 20;
    static final int TRAJECTORY_LIMIT = 8;
    static final int MEMORY_SLOT_LIMIT = 10;
    static final int SUMMARY_LIMIT = 200;
    static final int ALERT_SUMMARY_LIMIT = 500;
    static final int ITEM_LIMIT = 100;
    /** token 保守估算分母（中文混合上限档；R10 限额共用） */
    static final int CHARS_PER_TOKEN = 2;

    private final EvidenceRepository evidence;
    private final RcaToolInvocationLedger toolLedger;
    private final DelegationDecisionRepository delegations;
    private final AlertMaterialPort alertMaterials;
    private final WorkingMemoryPort workingMemory;
    /** MC21/22 合并面（可空=null 零漂移）：当前轮 ACCEPTED 回执入信封+记忆槽 */
    private final DelegationReceiptRepository delegationReceipts;
    /** MC31 区分面（可空=null 零漂移）：人工材料单列标注（与实测证据区分） */
    private final OperatorMaterialPort operatorMaterials;
    /** EN-08 装配缝（可空=null 零漂移）：run 钉版 Skill 受控视图入信封 */
    private final SkillPort skillPort;
    /** CL-08 消费面读缝（可空=null 零漂移）：按检查点 current_summary_id 读已验证摘要 */
    private final SummaryMaterialPort summaryMaterials;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ContextAssembler(EvidenceRepository evidence,
            RcaToolInvocationLedger toolLedger,
            DelegationDecisionRepository delegations,
            AlertMaterialPort alertMaterials, ObjectMapper mapper) {
        this(evidence, toolLedger, delegations, alertMaterials, null,
                Clock.systemUTC(), mapper);
    }

    public ContextAssembler(EvidenceRepository evidence,
            RcaToolInvocationLedger toolLedger,
            DelegationDecisionRepository delegations,
            AlertMaterialPort alertMaterials, WorkingMemoryPort workingMemory,
            Clock clock, ObjectMapper mapper) {
        this(evidence, toolLedger, delegations, alertMaterials, workingMemory,
                null, null, clock, mapper);
    }

    /** 全参构造（MC21~23 回执合并面 + MC31 人工材料区分面并集） */
    public ContextAssembler(EvidenceRepository evidence,
            RcaToolInvocationLedger toolLedger,
            DelegationDecisionRepository delegations,
            AlertMaterialPort alertMaterials, WorkingMemoryPort workingMemory,
            DelegationReceiptRepository delegationReceipts,
            OperatorMaterialPort operatorMaterials,
            Clock clock, ObjectMapper mapper) {
        this(evidence, toolLedger, delegations, alertMaterials, workingMemory,
                delegationReceipts, operatorMaterials, null, clock, mapper);
    }

    /** 全参构造 + Skill 装配缝（EN-08：run 钉版视图入信封；SK-08 运行时面） */
    public ContextAssembler(EvidenceRepository evidence,
            RcaToolInvocationLedger toolLedger,
            DelegationDecisionRepository delegations,
            AlertMaterialPort alertMaterials, WorkingMemoryPort workingMemory,
            DelegationReceiptRepository delegationReceipts,
            OperatorMaterialPort operatorMaterials,
            SkillPort skillPort,
            Clock clock, ObjectMapper mapper) {
        this(evidence, toolLedger, delegations, alertMaterials, workingMemory,
                delegationReceipts, operatorMaterials, skillPort, null, clock, mapper);
    }

    /** 全参构造 + Skill/摘要消费缝（CL-08 validated_summary 槽） */
    public ContextAssembler(EvidenceRepository evidence,
            RcaToolInvocationLedger toolLedger,
            DelegationDecisionRepository delegations,
            AlertMaterialPort alertMaterials, WorkingMemoryPort workingMemory,
            DelegationReceiptRepository delegationReceipts,
            OperatorMaterialPort operatorMaterials,
            SkillPort skillPort, SummaryMaterialPort summaryMaterials,
            Clock clock, ObjectMapper mapper) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.toolLedger = Objects.requireNonNull(toolLedger, "toolLedger");
        this.delegations = Objects.requireNonNull(delegations, "delegations");
        this.alertMaterials = Objects.requireNonNull(alertMaterials, "alertMaterials");
        this.workingMemory = workingMemory;
        this.delegationReceipts = delegationReceipts;
        this.operatorMaterials = operatorMaterials;
        this.skillPort = skillPort;
        this.summaryMaterials = summaryMaterials;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 一步的模型输入装配结果 */
    public record Assembly(String prompt, String snapshotDigest, int approxTokens,
            List<String> includedRefs, List<String> omittedRefs, WorkingMemory memory) {
    }

    /** 告警材料读口（宿主装配——run→incident→最新告警事件的确定性投影） */
    @FunctionalInterface
    public interface AlertMaterialPort {

        AlertMaterial byRun(UUID runId);
    }

    /**
     * 人工材料读口（MC31 区分面）：run→incident→已准入材料的确定性投影。
     * 材料与实测证据严格分槽下发（operator_materials），JUDGMENT 类带
     * "不构成证据引用"标注，不入 validRefs（无证判断不能绕过 Claim 准入）。
     */
    @FunctionalInterface
    public interface OperatorMaterialPort {

        List<OperatorMaterialView> byRun(UUID runId);
    }

    /** 人工材料视图（宿主投影；admission=ACCEPTED 才会进入） */
    public record OperatorMaterialView(String operator, String kind, String sourceRef,
            String content, String admission) {
    }

    /**
     * Skill 装配缝（EN-08 SK-08 运行时面 + CL-05 持久绑定）：每 (run, role,
     * configEpoch) 的选择是持久事实——roleId/configEpoch/releaseDigest 取自
     * 冻结任务绑定（可信身份，非装配材料自报）；选择/钉空/并发取一在
     * SkillSelectionService 收口。返回 none=钉空或消费阻断。
     */
    @FunctionalInterface
    public interface SkillPort {

        com.objwww.pr.control.release.application.SkillSelectionService.SkillView
        select(UUID runId, String roleId, Long configEpoch, String releaseDigest,
                String alertname, String service);
    }

    /**
     * CL-08 摘要消费读缝：按检查点 current_summary_id 读已提交摘要（CONSUME_
     * VALIDATED 模式下由 CL-01 围栏钉面）。行缺失/未接缝 → 槽省略，原材料继续
     * （消费是增益不是依赖）。全量旧正文替换消费待 MC34 三臂对照后启用。
     */
    @FunctionalInterface
    public interface SummaryMaterialPort {

        java.util.Optional<com.objwww.pr.control.alert.domain.agent.ContextSummary>
        byId(UUID summaryId);
    }

    /**
     * validated_summary 槽（CL-08 最小消费面）：经三闸验证的已提交摘要受控投影。
     * 只在检查点钉了消费指针且行可读时入信封——kept_refs 须为 validRefs 子集
     * （宿主生成时已保证，模型不得经摘要扩权）。
     */
    private void putValidatedSummary(Map<String, Object> envelope,
            PrimaryCheckpoint checkpoint) {
        if (summaryMaterials == null || checkpoint.currentSummaryId() == null) {
            return;
        }
        summaryMaterials.byId(checkpoint.currentSummaryId()).ifPresentOrElse(row -> {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("summary_id", row.id().toString());
            slot.put("source_snapshot_digest", row.sourceSnapshotDigest());
            slot.put("event_seq", List.of(row.eventSeqFrom(), row.eventSeqTo()));
            Frag text = clip(row.summaryText(), SUMMARY_LIMIT);
            slot.put("summary", text.text());
            slot.put("summary_truncated", text.truncated());
            slot.put("kept_refs", row.requiredRefs());
            slot.put("omitted_refs", row.omittedRefs());
            slot.put("note", "经三闸验证的已提交摘要（CL-07 CONSUME_VALIDATED）；"
                    + "全量旧正文替换消费待 MC34 三臂对照后启用");
            envelope.put("validated_summary", slot);
        }, () -> log.warn("current_summary_id 无已提交摘要行（原材料继续）run={} id={}",
                checkpoint.runId(), checkpoint.currentSummaryId()));
    }

    /**
     * 回执合并面（MC21/22）：当前轮 ACCEPTED 回执的有界投影——信封 child_receipts
     * 槽载荷 + 记忆槽反证/缺口来源。messageId 幂等准入保证同一结果至多一行，合并
     * 面不重复消费；LATE/OVERSIZED/REJECTED_SHAPE 行不进入（迟到只审计不冒充现场，
     * MC23）。
     */
    record ReceiptSection(List<Map<String, Object>> rows, List<String> counterRefs,
            List<String> openGaps) {

        static final ReceiptSection EMPTY =
                new ReceiptSection(List.of(), List.of(), List.of());
    }

    /** 信封 child_receipts 槽硬界（与批旋钮解耦的有界面） */
    static final int RECEIPT_LIMIT = 8;
    static final int RECEIPT_ITEMS_LIMIT = 6;
    static final int MATERIAL_LIMIT = 10;

    /** 告警材料（缺项如实 null——信封省略该键，不造数） */
    public record AlertMaterial(String alertname, String service, String severity,
            String summary) {

        public static AlertMaterial unknown() {
            return new AlertMaterial(null, null, null, null);
        }
    }

    /**
     * 装配一步的信封与 prompt（供 BoundedLlmRoleRunner 单步驱动调用）。
     * delegationBatchesRemaining 与裁决同源（Supervisor 旋钮 − 检查点已耗），禁自读配置
     * 致漂移；stableDigest 在注入 last_error 前计算（冻结面不含反馈）。
     *
     * <p>CL-03（§3.1）：一次读取形成显式证据成员快照（evidence/alert 材料各恰一次
     * 读库，投影/validRefs/included/omitted 同源不漂移）；装配零写入——工作记忆只
     * 产候选行（memory 字段），append 副作用迁至 PrimaryCheckpointCommitService
     * 的 STEP_COMPLETED 提交事务（模型未执行不落记忆，§3.1"不在 assemble 抢先 append"）。
     */
    public Assembly assemble(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, int delegationBatchesRemaining) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(checkpoint, "checkpoint");
        // CL-03 单次快照：证据集合与告警材料各恰一次读库，本步内不漂移（§3.1）
        List<EvidenceEnvelope> evidenceRows = new ArrayList<>(
                evidence.findByRunId(request.task().runId()));
        evidenceRows.sort(Comparator.comparing(EvidenceEnvelope::timeEnd,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparing(EvidenceEnvelope::evidenceId).reversed()));
        AlertMaterial material = alertMaterials.byRun(request.task().runId());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("role", roleOf(request.profile()));
        envelope.put("run_id", request.task().runId().toString());
        envelope.put("task_id", request.task().id().toString());
        envelope.put("round_id", checkpoint.roundId());
        envelope.put("alert", alertOf(request, material));
        envelope.put("objective", objectiveOf(request, material));
        envelope.put("budget", budgetOf(request, checkpoint, delegationBatchesRemaining));
        EvidenceWindow window = evidenceOf(request, evidenceRows);
        envelope.put("evidence", window.rows);
        ReceiptSection receiptSection = receiptsOf(request, checkpoint);
        envelope.put("child_receipts", receiptSection.rows());
        envelope.put("operator_materials", materialsOf(request));
        Map<String, Object> skill = skillOf(request, material);
        if (skill != null) {
            envelope.put("skill", skill);
        }
        MemoryCommit memory = candidateMemory(request, checkpoint, receiptSection);
        envelope.put("working_memory", memory.slots());
        envelope.put("trajectory", trajectoryOf(request));
        putValidatedSummary(envelope, checkpoint);
        envelope.put("tool_allowlist", request.profile().toolAllowlist().stream()
                .sorted().toList());
        // BA-112：args JSON Schema 钉版下发（Profile inputSchema 进 digest）
        envelope.put("tool_schemas", request.profile().inputSchema());
        envelope.put("valid_artifact_refs",
                validRefsOf(request, evidenceRows).stream().sorted().toList());

        String stablePrompt = request.profile().prompt() + "\n" + jsonOf(envelope)
                + BoundedLlmRoleRunner.PROTOCOL_SUFFIX;
        String snapshotDigest = Digest.sha256Of(stablePrompt).value();
        // 反馈环（V88）最后注入：不进 stableDigest，但随信封回喂
        if (checkpoint.lastError() != null) {
            envelope.put("last_error", checkpoint.lastError());
        }
        String prompt = request.profile().prompt() + "\n" + jsonOf(envelope)
                + BoundedLlmRoleRunner.PROTOCOL_SUFFIX;
        return new Assembly(prompt, snapshotDigest,
                prompt.length() / CHARS_PER_TOKEN + 1, window.includedRefs,
                window.omittedRefs, memory.row());
    }

    // ------------------------------------------------------------------ 各槽装配

    private static String roleOf(AgentProfile profile) {
        return profile.name() + "@" + profile.version();
    }

    /** 告警材料 + 冻结窗（§19.1：身份/窗为模型可读事实，非 UUID 串） */
    private Map<String, Object> alertOf(RoleRunner.RoleDriveRequest request,
            AlertMaterial material) {
        Map<String, Object> alert = new LinkedHashMap<>();
        if (material.alertname() != null) {
            alert.put("alertname", material.alertname());
        }
        if (material.service() != null) {
            alert.put("service", material.service());
        }
        if (material.severity() != null) {
            alert.put("severity", material.severity());
        }
        if (material.summary() != null) {
            Frag summary = clip(material.summary(), ALERT_SUMMARY_LIMIT);
            alert.put("summary", summary.text());
            if (summary.truncated()) {
                alert.put("summary_truncated", true);
            }
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start_epoch", Long.parseLong(request.startEpoch()));
        window.put("end_epoch", Long.parseLong(request.endEpoch()));
        alert.put("window", window);
        return alert;
    }

    /**
     * 调查目标（一句话，宿主铸定非模型自撰）：从冻结材料确定性导出——
     * 装配时点无独立 objective 列，告警身份+冻结窗即目标身份（偏差登记执行日志）。
     */
    private String objectiveOf(RoleRunner.RoleDriveRequest request, AlertMaterial material) {
        String subject = material.alertname() != null ? material.alertname() : "告警事故";
        String scope = material.service() != null ? "（service=" + material.service() + "）" : "";
        return "调查" + subject + scope + "在冻结窗 " + request.startEpoch() + "/"
                + request.endEpoch() + " 内的根因：用 tool_allowlist 工具取证，"
                + "结论必须引用 valid_artifact_refs 中的证据，证据不足如实声明缺口";
    }

    private Map<String, Object> budgetOf(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, int delegationBatchesRemaining) {
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("steps_remaining", Math.max(0,
                request.profile().maxSteps() - checkpoint.stepsUsed()));
        budget.put("delegation_batches_remaining", Math.max(0, delegationBatchesRemaining));
        budget.put("token_budget_remaining", null);
        return budget;
    }

    /**
     * 证据窗（CL-03 §3.2 有效信息投影）：倒序 ≤{@value #EVIDENCE_LIMIT} 条分型投影
     * （日志聚合保频次、指标保数值/单位、变更保前后差异、RAG 标 REFERENCE、未知形状
     * 诚实有界投影）+ 溢出留痕。投影源 = 本步成员快照（单次读库，§3.1）。
     */
    private EvidenceWindow evidenceOf(RoleRunner.RoleDriveRequest request,
            List<EvidenceEnvelope> rows) {
        List<Map<String, Object>> summarized = new ArrayList<>();
        List<String> included = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            EvidenceEnvelope row = rows.get(i);
            if (i >= EVIDENCE_LIMIT) {
                omitted.add(row.evidenceId().toString());
                continue;
            }
            included.add(row.evidenceId().toString());
            summarized.add(projectEvidence(row));
        }
        return new EvidenceWindow(summarized, included, omitted);
    }

    private record EvidenceWindow(List<Map<String, Object>> rows,
            List<String> includedRefs, List<String> omittedRefs) {
    }

    // ------------------------------------------------------ CL-03 §3.2 分型投影

    /** 单条证据 observations 展示上限（聚合组/序列行共享同一上限） */
    static final int OBSERVATION_LIMIT = 6;

    /** 证据投影公共骨架：ref/type/source_digest/window + 分型载荷 + 截断留痕 */
    private Map<String, Object> projectEvidence(EvidenceEnvelope row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ref", row.evidenceId().toString());
        item.put("type", row.evidenceType());
        item.put("source_digest", row.payloadDigest());
        if (row.timeStart() != null || row.timeEnd() != null) {
            Map<String, Object> window = new LinkedHashMap<>();
            if (row.timeStart() != null) {
                window.put("start", row.timeStart().toString());
            }
            if (row.timeEnd() != null) {
                window.put("end", row.timeEnd().toString());
            }
            item.put("window", window);
        }
        JsonNode payload = parsePayload(row.canonicalPayload());
        String type = row.evidenceType() == null ? "" : row.evidenceType();
        if (type.startsWith("logs.")) {
            projectLogs(item, payload);
        } else if (type.startsWith("metrics.")) {
            projectMetrics(item, payload);
        } else if (type.startsWith("change.")) {
            projectChange(item, payload);
        } else if (type.startsWith("runbook.") || type.startsWith("rca_history.")) {
            projectReference(item, payload);
        } else {
            projectUnknown(item, row.canonicalPayload());
        }
        return item;
    }

    private JsonNode parsePayload(String canonicalPayload) {
        try {
            return mapper.readTree(canonicalPayload == null ? "{}" : canonicalPayload);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * logs.*：data.result[{ts,service,line}] 按错误签名聚合——严重级签名优先（不同
     * 严重错误不被重复普通日志挤掉，§3.2 表格），同级按频次降序；每组保代表行+
     * 频次+首末时间。签名 = uuid/hex/数字 归一化后的行模板。
     */
    private void projectLogs(Map<String, Object> item, JsonNode payload) {
        JsonNode result = payload == null ? null : payload.path("data").path("result");
        if (result == null || !result.isArray()) {
            projectUnknown(item, payload == null ? null : payload.toString());
            return;
        }
        Map<String, List<JsonNode>> groups = new LinkedHashMap<>();
        Map<String, Boolean> severe = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode entry : result) {
            total++;
            String line = entry.path("line").asText("");
            String signature = logSignature(line);
            groups.computeIfAbsent(signature, k -> new ArrayList<>()).add(entry);
            severe.putIfAbsent(signature, isSevereLog(line));
        }
        List<Map.Entry<String, List<JsonNode>>> ranked = new ArrayList<>(groups.entrySet());
        ranked.sort((a, b) -> {
            boolean sa = severe.get(a.getKey());
            boolean sb = severe.get(b.getKey());
            if (sa != sb) {
                return sa ? -1 : 1;      // 严重级签名恒先（§3.2：不被重复普通日志挤掉）
            }
            return Integer.compare(b.getValue().size(), a.getValue().size());
        });
        List<Map<String, Object>> observations = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<String, List<JsonNode>> group : ranked) {
            if (observations.size() >= OBSERVATION_LIMIT) {
                break;
            }
            List<JsonNode> rowsOfGroup = group.getValue();
            // 首末时间与输入序解耦：ISO-8601 同形字符串字典序即时间序
            JsonNode rep = rowsOfGroup.get(0);
            String firstAt = null;
            String lastAt = null;
            for (JsonNode entry : rowsOfGroup) {
                String ts = entry.path("ts").asText(null);
                if (ts == null) {
                    continue;
                }
                if (firstAt == null || ts.compareTo(firstAt) < 0) {
                    firstAt = ts;
                }
                if (lastAt == null || ts.compareTo(lastAt) > 0) {
                    lastAt = ts;
                    rep = entry;
                }
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("at", rep.path("ts").asText(null));
            observation.put("service", rep.path("service").asText(null));
            observation.put("message", clip(rep.path("line").asText(""), ITEM_LIMIT).text());
            if (rowsOfGroup.size() > 1) {
                observation.put("count", rowsOfGroup.size());
                observation.put("first_at", firstAt);
                observation.put("last_at", lastAt);
            }
            observations.add(observation);
            shown += rowsOfGroup.size();
        }
        item.put("observations", observations);
        item.put("total_count", total);
        item.put("shown_count", shown);
        if (shown < total) {
            item.put("truncated", true);
            item.put("omission_reason", "ITEM_LIMIT");
        }
    }

    /** 严重行判定（封闭词面：大小写不敏感子串） */
    private static boolean isSevereLog(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("fatal")
                || lower.contains("panic") || lower.contains("exception")
                || lower.contains("critical");
    }

    /** 日志签名：uuid/hex/数字 归一化（确定性——同错误模板同签名） */
    static String logSignature(String line) {
        return line.toLowerCase()
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "<uuid>")
                .replaceAll("\\b[0-9a-f]{12,}\\b", "<hex>")
                .replaceAll("\\d+", "<n>");
    }

    /**
     * metrics.*：data.result 序列投影——标签集（有界）+ value/values 实际数值 + 单位
     * （源确有提供时，catalog 行有 unit 字段）；不展示裸 status（§3.2：不得只展示
     * status）。
     */
    private void projectMetrics(Map<String, Object> item, JsonNode payload) {
        JsonNode result = payload == null ? null : payload.path("data").path("result");
        if (result == null || !result.isArray()) {
            projectUnknown(item, payload == null ? null : payload.toString());
            return;
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        int total = 0;
        for (JsonNode entry : result) {
            if (observations.size() >= OBSERVATION_LIMIT) {
                total++;
                continue;
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            JsonNode labels = entry.path("metric");
            if (labels.isObject() && !labels.isEmpty()) {
                Map<String, String> bounded = new LinkedHashMap<>();
                labels.fields().forEachRemaining(f ->
                        bounded.put(f.getKey(), clip(f.getValue().asText(""), 32).text()));
                observation.put("labels", bounded);
            }
            JsonNode value = entry.path("value");
            if (value.isArray() && value.size() >= 2) {
                observation.put("value", value.get(1).asText());
                observation.put("at", value.get(0).asText());
            } else if (entry.path("values").isArray()) {
                JsonNode values = entry.path("values");
                List<String> picked = new ArrayList<>();
                picked.add(values.get(0).get(1).asText());
                if (values.size() > 2) {
                    picked.add(values.get(values.size() / 2).get(1).asText());
                }
                picked.add(values.get(values.size() - 1).get(1).asText());
                observation.put("values_first_mid_last", picked);
                observation.put("value_count", values.size());
            } else if (entry.isObject() && !entry.has("metric")) {
                // catalog/label_values 行：扁平字段（name/type/unit/value…）有界直投
                entry.fields().forEachRemaining(f -> observation.put(f.getKey(),
                        clip(f.getValue().asText(""), ITEM_LIMIT).text()));
            }
            if (entry.has("unit") && entry.path("unit").asText("").isEmpty()) {
                observation.remove("unit");   // 空单位不冒充"源确有提供"
            }
            observations.add(observation);
            total++;
        }
        item.put("observations", observations);
        item.put("total_count", total);
        item.put("shown_count", observations.size());
        if (observations.size() < total) {
            item.put("truncated", true);
            item.put("omission_reason", "ITEM_LIMIT");
        }
    }

    /**
     * change.*：变更事实投影——时间/对象/动作 + 窗前基线单列（不冒充窗内变更，
     * §3.2）。执行器字段名不假定：常见键位（time/created_at、object/service/name、
     * action/type/state）逐一探测，缺失字段不造数。
     */
    private void projectChange(Map<String, Object> item, JsonNode payload) {
        JsonNode result = payload == null ? null : payload.path("data").path("result");
        JsonNode baseline = payload == null ? null : payload.path("data").path("baseline");
        if (result == null || !result.isArray()) {
            projectUnknown(item, payload == null ? null : payload.toString());
            return;
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        for (JsonNode entry : result) {
            if (observations.size() >= OBSERVATION_LIMIT) {
                break;
            }
            Map<String, Object> observation = new LinkedHashMap<>();
            putIfPresent(observation, entry, "at", "time", "created_at", "ts");
            putIfPresent(observation, entry, "object", "object", "service", "name");
            putIfPresent(observation, entry, "action", "action", "type", "state");
            observation.put("detail", clip(entry.toString(), ITEM_LIMIT).text());
            observations.add(observation);
        }
        item.put("observations", observations);
        item.put("total_count", result.size());
        item.put("shown_count", observations.size());
        if (observations.size() < result.size()) {
            item.put("truncated", true);
            item.put("omission_reason", "ITEM_LIMIT");
        }
        if (baseline != null && baseline.isArray() && !baseline.isEmpty()) {
            // 窗前基线单列（§3.2：不冒充窗内变更）
            item.put("pre_window_baseline_count", baseline.size());
            item.put("pre_window_baseline_sample", clip(baseline.get(0).toString(),
                    ITEM_LIMIT).text());
        }
    }

    /** runbook 与 rca_history 证据：参考材料——标 REFERENCE，不作当前现场独立证据 */
    private void projectReference(Map<String, Object> item, JsonNode payload) {
        item.put("reference", true);
        if (payload != null && payload.has("data")) {
            JsonNode data = payload.path("data");
            if (data.has("digest") || data.has("corpus_digest")) {
                item.put("doc_digest", data.path("digest").asText(
                        data.path("corpus_digest").asText(null)));
            }
            if (data.has("body") || data.has("content")) {
                String body = data.has("body") ? data.path("body").asText()
                        : data.path("content").asText();
                Frag clipped = clip(body, SUMMARY_LIMIT);
                item.put("body", clipped.text());
                item.put("truncated", clipped.truncated());
            }
            if (data.path("result").isArray()) {
                item.put("entries", clip(data.path("result").toString(),
                        SUMMARY_LIMIT).text());
            }
        }
    }

    /**
     * 未识别形状（§3.2 末行）：有界 JSON 投影 + 明确 unknown_shape 标记——禁止非对象
     * 时输出空字符串却无异常标记（解析失败同路：原文截断 + 解析失败标记）。
     */
    private void projectUnknown(Map<String, Object> item, String canonicalPayload) {
        item.put("unknown_shape", true);
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            item.put("parse_failed", true);
            item.put("bounded_json", "");
            return;
        }
        Frag clipped = clip(canonicalPayload.strip(), SUMMARY_LIMIT);
        item.put("bounded_json", clipped.text());
        if (clipped.truncated()) {
            item.put("truncated", true);
        }
    }

    /** 多候选键位逐一探测（缺失不造数；首个命中键保留原名语义归一到目标键） */
    private static void putIfPresent(Map<String, Object> sink, JsonNode entry,
            String targetKey, String... candidates) {
        for (String candidate : candidates) {
            JsonNode value = entry.path(candidate);
            if (!value.isMissingNode() && !value.isNull() && !value.asText("").isEmpty()) {
                sink.put(targetKey, clip(value.asText(), ITEM_LIMIT).text());
                return;
            }
        }
    }

    /** 本步工作记忆候选面：信封实际下发的槽 + 候选快照行（未接持久面时 row=null） */
    record MemoryCommit(Map<String, List<String>> slots, WorkingMemory row) {
    }

    /**
     * 工作记忆候选（R10 + CL-03 §3.1 无写入计算 + CL-06 §5.2 累计合并）：
     * 从 checkpoint.memory_id 精确读上一版（不以 latestByTask 替代指针），父版
     * 槽在前 + 本轮宿主可确定 delta 按内容去重追加（反证跨轮保留——MC22）；digest
     * 构造期一次算定，<b>不在此 append</b>——落库副作用随成功动作在
     * PrimaryCheckpointCommitService 的 STEP_COMPLETED 提交事务内发生。首期无
     * memory_delta 决策协议，槽仍为宿主可确定四槽（§5.2 第一批边界）。
     */
    MemoryCommit candidateMemory(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ReceiptSection receiptSection) {
        Map<String, List<String>> rebuilt = rebuildMemorySlots(request, checkpoint,
                receiptSection);
        if (workingMemory == null) {
            return new MemoryCommit(rebuilt, null);
        }
        WorkingMemory previous = checkpoint.memoryId() == null ? null
                : workingMemory.findById(checkpoint.memoryId()).orElse(null);
        Map<String, List<String>> merged = mergeAccumulated(previous, rebuilt);
        WorkingMemory candidate = WorkingMemory.ofV2(
                UUID.randomUUID(), request.task().runId(), request.task().id(),
                checkpoint.revision(), merged, null,
                previous == null ? null : previous.id(), clock.instant());
        Map<String, List<String>> bounded = new LinkedHashMap<>();
        candidate.slots().forEach((key, value) -> bounded.put(key, bound(value)));
        return new MemoryCommit(bounded, candidate);
    }

    /** 累计合并：父版槽在前（早期反证不被新轮挤掉），本轮 delta 按内容去重追加 */
    private static Map<String, List<String>> mergeAccumulated(WorkingMemory previous,
            Map<String, List<String>> delta) {
        if (previous == null) {
            return delta;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (String key : WorkingMemory.SLOT_KEYS) {
            java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>(
                    previous.slots().getOrDefault(key, List.of()));
            items.addAll(delta.getOrDefault(key, List.of()));
            merged.put(key, new ArrayList<>(items));
        }
        return merged;
    }

    /**
     * 工作记忆确定性重建（槽契约 R10 持久化后由快照承载）：假设=检查点 FINAL 提案史、
     * ruled_out=裁决拒绝史、反证=当前轮回执 counter_refs 并集（MC22 反证可追溯）、
     * open_gaps=回执 missing_information 并集；每槽 ≤10 项、单项 ≤100 字符。
     */
    Map<String, List<String>> rebuildMemorySlots(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ReceiptSection receiptSection) {
        List<String> hypotheses = new ArrayList<>();
        for (Map<String, Object> claim : checkpoint.finalClaims()) {
            Object statement = claim.get("statement");
            if (statement != null) {
                hypotheses.add(clip(String.valueOf(statement), ITEM_LIMIT).text());
            }
        }
        List<String> ruledOut = delegations.findByRunAndPrimaryTask(
                        request.task().runId(), request.task().id()).stream()
                .filter(d -> d.status() == DelegationDecision.Status.REJECTED)
                .map(d -> clip(d.gapId() + ": " + d.rejectReason(), ITEM_LIMIT).text())
                .toList();
        Map<String, List<String>> memory = new LinkedHashMap<>();
        memory.put("hypotheses", bound(hypotheses));
        memory.put("ruled_out", bound(ruledOut));
        memory.put("counter_evidence_refs", bound(receiptSection.counterRefs()));
        memory.put("open_gaps", bound(receiptSection.openGaps()));
        return memory;
    }

    /**
     * 当前轮 ACCEPTED 回执投影（MC21/22 合并面）：未接台账面（legacy 构造）→ 空。
     * 行=有界载荷；counterRefs=反证并集（去重保序）；openGaps=缺口并集。
     */
    private ReceiptSection receiptsOf(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint) {
        if (delegationReceipts == null) {
            return ReceiptSection.EMPTY;
        }
        List<DelegationReceipt> accepted = delegationReceipts.findAcceptedByRunAndRound(
                request.task().runId(), request.task().id(), checkpoint.roundId());
        if (accepted.isEmpty()) {
            return ReceiptSection.EMPTY;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> counterRefs = new ArrayList<>();
        List<String> openGaps = new ArrayList<>();
        int omitted = 0;
        for (DelegationReceipt receipt : accepted) {
            if (rows.size() >= RECEIPT_LIMIT) {
                omitted++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gap_id", receipt.gapId());
            row.put("role_id", receipt.roleId());
            row.put("status", receipt.childStatus().name().toLowerCase());
            List<String> shown = receipt.findings().stream()
                    .limit(RECEIPT_ITEMS_LIMIT)
                    .map(f -> clip(f, ITEM_LIMIT).text())
                    .toList();
            row.put("findings", shown);
            if (receipt.findings().size() > shown.size()) {
                row.put("findings_omitted", receipt.findings().size() - shown.size());
            }
            row.put("support_refs", bound(receipt.supportRefs()));
            row.put("counter_refs", bound(receipt.counterRefs()));
            row.put("missing_information", bound(receipt.missingInformation()));
            rows.add(row);
            receipt.counterRefs().stream()
                    .filter(ref -> !counterRefs.contains(ref))
                    .forEach(counterRefs::add);
            receipt.missingInformation().stream()
                    .filter(gap -> !openGaps.contains(gap))
                    .forEach(openGaps::add);
        }
        if (omitted > 0) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("receipts_omitted", omitted);
            rows.add(marker);
        }
        return new ReceiptSection(List.copyOf(rows), List.copyOf(counterRefs),
                List.copyOf(openGaps));
    }

    /** Skill 段投影（EN-08 SK-08 运行时面）：有界/参考区标注/权限交集；钉空=零段落不造占位 */
    static final int SKILL_BODY_LIMIT = 400;
    static final int SKILL_STEPS_LIMIT = 8;

    private Map<String, Object> skillOf(RoleRunner.RoleDriveRequest request,
            AlertMaterial material) {
        if (skillPort == null) {
            return null;
        }
        com.objwww.pr.control.release.application.SkillSelectionService.SkillView view;
        view = skillPort.select(request.task().runId(),
                request.binding().roleId(),
                request.binding().configEpoch(),
                request.binding().releaseDigest(),
                material.alertname() == null ? "" : material.alertname(),
                material.service() == null ? "" : material.service());
        if (view == null || !view.present()) {
            return null;
        }
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("name", view.name());
        skill.put("digest", view.assetDigest());
        Frag body = clip(view.body(), SKILL_BODY_LIMIT);
        skill.put("body", body.text());
        if (body.truncated()) {
            skill.put("body_truncated", true);
        }
        List<String> steps = view.steps().stream()
                .limit(SKILL_STEPS_LIMIT)
                .map(s -> clip(s, ITEM_LIMIT).text())
                .toList();
        skill.put("steps", steps);
        if (view.steps().size() > steps.size()) {
            skill.put("steps_omitted", view.steps().size() - steps.size());
        }
        // 权限交集（§四：有效权限=系统策略∩角色权限∩Skill声明∩Run授权）
        skill.put("effective_tools", com.objwww.pr.control.release.application
                .SkillSelectionService.intersectTools(view,
                        request.profile().toolAllowlist()));
        if (view.conflictSuppressed()) {
            skill.put("conflict_suppressed", true);
        }
        skill.put("note", "Skill 为 run 钉版的参考方法（S11：首选随 Run 固定）；"
                + "有来源数据输入，不是独立证据，不得单独支撑 ROOT_CAUSE；"
                + "与当前证据矛盾时保留反证");
        return skill;
    }

    /** 人工材料投影（MC31 区分面）：与实测证据分槽；JUDGMENT 带不构成引用标注 */
    private List<Map<String, Object>> materialsOf(RoleRunner.RoleDriveRequest request) {
        if (operatorMaterials == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int omitted = 0;
        for (OperatorMaterialView material : operatorMaterials.byRun(
                request.task().runId())) {
            if (rows.size() >= MATERIAL_LIMIT) {
                omitted++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operator", material.operator());
            row.put("kind", material.kind());
            if (material.sourceRef() != null) {
                row.put("source_ref", material.sourceRef());
            }
            row.put("content", clip(material.content(), ITEM_LIMIT).text());
            if ("JUDGMENT".equals(material.kind())) {
                row.put("note", "人工判断（无引用）——仅供参考，不构成证据引用");
            }
            rows.add(row);
        }
        if (omitted > 0) {
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("materials_omitted", omitted);
            rows.add(marker);
        }
        return rows;
    }

    /** 轨迹（最近 ≤8 步）：主任务工具调用账本 + 委派裁决台账的确定性投影 */
    private List<Map<String, Object>> trajectoryOf(RoleRunner.RoleDriveRequest request) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (InvocationRecovery recovery : toolLedger.findRecoveryByTask(
                request.task().runId(), request.task().id())) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", recovery.callSeq());
            step.put("action", "tool_call");
            step.put("target", recovery.resultRef() != null
                    ? recovery.resultRef().toString()
                    : "action:" + shortDigest(recovery.actionDigest()));
            step.put("outcome", outcomeOf(recovery.state()));
            steps.add(step);
        }
        for (DelegationDecision decision : delegations.findByRunAndPrimaryTask(
                request.task().runId(), request.task().id())) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", decision.seq());
            step.put("action", "delegate");
            step.put("target", decision.roleId() + ":" + decision.gapId());
            step.put("outcome", decision.status() == DelegationDecision.Status.APPROVED
                    ? "success" : "rejected");
            if (decision.rejectReason() != null) {
                step.put("note", clip(decision.rejectReason(), ITEM_LIMIT).text());
            }
            steps.add(step);
        }
        steps.sort(Comparator.comparingLong(s -> ((Number) s.get("step")).longValue()));
        return steps.size() > TRAJECTORY_LIMIT
                ? steps.subList(steps.size() - TRAJECTORY_LIMIT, steps.size())
                : steps;
    }

    private static String outcomeOf(ToolInvocationState state) {
        return switch (state) {
            case SUCCESS -> "success";
            case FAILED -> "failed";
            case PENDING, UNKNOWN -> "unknown";
        };
    }

    /** 本 run 合法引用全集（X5 准入面同源）：绑定 inputRefs + 快照证据行 id（§3.1 单次读） */
    private Set<String> validRefsOf(RoleRunner.RoleDriveRequest request,
            List<EvidenceEnvelope> evidenceRows) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>(
                request.binding().inputRefs());
        evidenceRows.forEach(e -> refs.add(e.evidenceId().toString()));
        return refs;
    }

    // ------------------------------------------------------------------ 确定性摘要

    /** 摘要载体：文本 + 是否发生截断/省略 */
    record Frag(String text, boolean truncated) {
    }

    /** 定长截断（含省略号恰 ≤limit，超界置 truncated 不静默丢字） */
    static Frag clip(String text, int limit) {
        if (text == null) {
            return new Frag("", false);
        }
        if (text.length() <= limit) {
            return new Frag(text, false);
        }
        return new Frag(text.substring(0, Math.max(0, limit - 1)) + "…", true);
    }

    /** 槽上限（≤10 项，保序保新） */
    private static List<String> bound(List<String> items) {
        return items.size() > MEMORY_SLOT_LIMIT
                ? items.subList(items.size() - MEMORY_SLOT_LIMIT, items.size())
                : items;
    }

    /**
     * 确定性裁剪策略资产内容（EN-01/03，V98 CONTEXT_POLICY kind）：R1 界面限长
     * 全部编译期常量单源钉版——没有 LLM 摘要时，重放解释"当时第一刀怎么裁"的
     * 唯一依据（增强线 v1.1 L464"保证重放能解释当时模型看到了什么"）。
     * compactionPromptDigest（P0 批 PROMPT 资产）与本资产的 digest 构成重放解释锚对，
     * 由 AlertAm4Config 启动登记并 log。
     */
    public static Map<String, Object> policyAssetContent() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("evidence_limit", EVIDENCE_LIMIT);
        limits.put("trajectory_limit", TRAJECTORY_LIMIT);
        limits.put("memory_slot_limit", MEMORY_SLOT_LIMIT);
        limits.put("summary_limit", SUMMARY_LIMIT);
        limits.put("alert_summary_limit", ALERT_SUMMARY_LIMIT);
        limits.put("item_limit", ITEM_LIMIT);
        limits.put("chars_per_token_estimate", CHARS_PER_TOKEN);
        limits.put("evidence_order", "time_end desc, evidence_id desc（最新最相关）");
        limits.put("omitted_behavior", "溢出记 omittedRefs，valid_artifact_refs 不裁剪");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("limits", limits);
        content.put("envelope_version", "am4-envelope.v2");
        content.put("compaction_schema_version",
                String.valueOf(ContextCompactionService.SCHEMA_VERSION));
        content.put("note", "确定性裁剪恒为第一刀（R1 界面），LLM 摘要殿后且默认关"
                + "（enabled=false 时本策略独立完整生效）");
        return content;
    }

    private static String shortDigest(String digest) {
        return digest == null ? "unknown" : digest.substring(0, Math.min(8, digest.length()));
    }

    private String jsonOf(Map<String, Object> envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务信封序列化失败", e);
        }
    }
}

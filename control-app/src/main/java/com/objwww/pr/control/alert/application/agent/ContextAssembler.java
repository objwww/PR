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
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.toolLedger = Objects.requireNonNull(toolLedger, "toolLedger");
        this.delegations = Objects.requireNonNull(delegations, "delegations");
        this.alertMaterials = Objects.requireNonNull(alertMaterials, "alertMaterials");
        this.workingMemory = workingMemory;
        this.delegationReceipts = delegationReceipts;
        this.operatorMaterials = operatorMaterials;
        this.skillPort = skillPort;
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
     * Skill 装配缝（EN-08，SK-08 运行时面）：run 钉版选择（S11 首选随 Run 固定）。
     * 返回 none=无匹配（钉空退回通用调查）；视图经
     * SkillSelectionService（release/application）生产——S06 命中/S07 冲突/S08 只出
     * ACTIVE 在其内部收口。
     */
    @FunctionalInterface
    public interface SkillPort {

        com.objwww.pr.control.release.application.SkillSelectionService.SkillView
        select(UUID runId, String alertname, String service);
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
     */
    public Assembly assemble(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, int delegationBatchesRemaining) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("role", roleOf(request.profile()));
        envelope.put("run_id", request.task().runId().toString());
        envelope.put("task_id", request.task().id().toString());
        envelope.put("round_id", checkpoint.roundId());
        envelope.put("alert", alertOf(request));
        envelope.put("objective", objectiveOf(request));
        envelope.put("budget", budgetOf(request, checkpoint, delegationBatchesRemaining));
        EvidenceWindow window = evidenceOf(request);
        envelope.put("evidence", window.rows);
        ReceiptSection receiptSection = receiptsOf(request, checkpoint);
        envelope.put("child_receipts", receiptSection.rows());
        envelope.put("operator_materials", materialsOf(request));
        Map<String, Object> skill = skillOf(request);
        if (skill != null) {
            envelope.put("skill", skill);
        }
        MemoryCommit memory = commitMemory(request, checkpoint, receiptSection);
        envelope.put("working_memory", memory.slots());
        envelope.put("trajectory", trajectoryOf(request));
        envelope.put("tool_allowlist", request.profile().toolAllowlist().stream()
                .sorted().toList());
        // BA-112：args JSON Schema 钉版下发（Profile inputSchema 进 digest）
        envelope.put("tool_schemas", request.profile().inputSchema());
        envelope.put("valid_artifact_refs", validRefsOf(request).stream().sorted().toList());

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
    private Map<String, Object> alertOf(RoleRunner.RoleDriveRequest request) {
        Map<String, Object> alert = new LinkedHashMap<>();
        AlertMaterial material = alertMaterials.byRun(request.task().runId());
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
    private String objectiveOf(RoleRunner.RoleDriveRequest request) {
        AlertMaterial material = alertMaterials.byRun(request.task().runId());
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

    /** 证据窗：倒序 ≤20 条有界摘要 + 溢出留痕 */
    private EvidenceWindow evidenceOf(RoleRunner.RoleDriveRequest request) {
        List<EvidenceEnvelope> rows = new ArrayList<>(evidence.findByRunId(
                request.task().runId()));
        rows.sort(Comparator.comparing(EvidenceEnvelope::timeEnd,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Comparator.comparing(EvidenceEnvelope::evidenceId).reversed()));
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
            Frag summary = summarize(row.canonicalPayload());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", row.evidenceId().toString());
            item.put("type", row.evidenceType());
            if (row.timeEnd() != null) {
                item.put("at", row.timeEnd().toString());
            }
            item.put("summary", summary.text());
            item.put("truncated", summary.truncated());
            summarized.add(item);
        }
        return new EvidenceWindow(summarized, included, omitted);
    }

    private record EvidenceWindow(List<Map<String, Object>> rows,
            List<String> includedRefs, List<String> omittedRefs) {
    }

    /** 本步工作记忆提交面：信封实际下发的槽 + 深冻结快照行（未接持久面时 row=null） */
    record MemoryCommit(Map<String, List<String>> slots, WorkingMemory row) {
    }

    /**
     * 工作记忆提交（R10）：确定性重建为候选 → append 深冻结（同修订重放返回既有行
     * ——MC07 崩溃重驱读同快照不另生成；MC06 已提交快照不漂移），信封下发<b>返回行</b>
     * 的槽（冻结真相，与检查点 memory_id/digest 钉面一致）。未接持久面（legacy 构造）
     * → 只下发重建槽，不落档。MC22：反证/缺口槽由当前轮 ACCEPTED 回执供给。
     */
    MemoryCommit commitMemory(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ReceiptSection receiptSection) {
        Map<String, List<String>> rebuilt = rebuildMemorySlots(request, checkpoint,
                receiptSection);
        if (workingMemory == null) {
            return new MemoryCommit(rebuilt, null);
        }
        WorkingMemory committed = workingMemory.append(WorkingMemory.of(
                UUID.randomUUID(), request.task().runId(), request.task().id(),
                checkpoint.decisionSeq(), rebuilt, null, clock.instant()));
        Map<String, List<String>> bounded = new LinkedHashMap<>();
        committed.slots().forEach((key, value) -> bounded.put(key, bound(value)));
        return new MemoryCommit(bounded, committed);
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

    private Map<String, Object> skillOf(RoleRunner.RoleDriveRequest request) {
        if (skillPort == null) {
            return null;
        }
        com.objwww.pr.control.release.application.SkillSelectionService.SkillView view;
        AlertMaterial material = alertMaterials.byRun(request.task().runId());
        view = skillPort.select(request.task().runId(),
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

    /** 本 run 合法引用全集（X5 准入面同源）：绑定 inputRefs + run 全量证据行 id */
    private Set<String> validRefsOf(RoleRunner.RoleDriveRequest request) {
        java.util.LinkedHashSet<String> refs = new java.util.LinkedHashSet<>(
                request.binding().inputRefs());
        evidence.findByRunId(request.task().runId())
                .forEach(e -> refs.add(e.evidenceId().toString()));
        return refs;
    }

    // ------------------------------------------------------------------ 确定性摘要

    /** 摘要载体：文本 + 是否发生截断/省略 */
    record Frag(String text, boolean truncated) {
    }

    /**
     * 证据 payload 确定性摘要（零 LLM）：canonical JSON 顶层标量 "k=v" 串接，
     * 容器值省略计数留痕；超 {@value #SUMMARY_LIMIT} 截断并置 truncated。
     * 形状未知/解析失败 = 原文截断（诚实降级，不造数）。
     */
    Frag summarize(String canonicalPayload) {
        if (canonicalPayload == null || canonicalPayload.isBlank()) {
            return new Frag("", false);
        }
        try {
            JsonNode root = mapper.readTree(canonicalPayload);
            StringBuilder sb = new StringBuilder();
            boolean elided = appendTopLevel(root, sb);
            Frag clipped = clip(sb.toString(), SUMMARY_LIMIT);
            return new Frag(clipped.text(), clipped.truncated() || elided);
        } catch (Exception e) {
            log.debug("证据 payload 非对象形状，原文截断摘要");
            Frag clipped = clip(canonicalPayload.strip(), SUMMARY_LIMIT);
            return new Frag(clipped.text(), clipped.truncated()
                    || canonicalPayload.length() > SUMMARY_LIMIT);
        }
    }

    /** 顶层标量串接；容器值省略计数；返回是否发生省略 */
    private boolean appendTopLevel(JsonNode node, StringBuilder sb) {
        if (!node.isObject()) {
            return false;
        }
        int containers = 0;
        boolean first = true;
        for (var fields = node.fields(); fields.hasNext(); ) {
            var field = fields.next();
            JsonNode value = field.getValue();
            if (value.isValueNode()) {
                if (!first) {
                    sb.append("; ");
                }
                sb.append(field.getKey()).append('=')
                        .append(value.asText().replace('\n', ' '));
                first = false;
            } else {
                containers++;
            }
        }
        if (containers > 0) {
            if (!first) {
                sb.append("; ");
            }
            sb.append("(+").append(containers).append(" struct fields)");
            return true;
        }
        return false;
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

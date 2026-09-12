package com.objwww.pr.control.release.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.RcaRunSource;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Skill 源头（EN-08 第二期，用例文档 §五"封存资格/候选生成"落地面）：从真实告警
 * 闭环的<b>封存轨迹</b>确定性提炼候选提案——只提炼可审计动作/证据/结果（证据类型
 * 时序+成功工具调用计数），不依赖隐藏思维链；事故特有信息清洗由
 * {@link SkillCandidateService#scrub}（S03）承接，泄漏验面整卷拒绝。
 *
 * <p>来源契约（§五"封存资格"行，未满足=零落库拒绝）：
 * <ul>
 *   <li>run 必存在且<b>终态</b>——RUNNING/QUEUED/REPORTING 未封存即拒；</li>
 *   <li>FAILED 不作正向原料（失败经验沉淀归 stop_conditions/反例面，不当成功故事）；</li>
 *   <li>仅 SUCCEEDED 可提炼；零证据轨迹不生成（无引用步骤=无证经验）；</li>
 *   <li>人工复核依据由调用方声明（humanReviewed）——未复核走 S02 REJECTED 隔离，
 *       不当可信正向原料；生成不等于发布（候选恒 DRAFT/REJECTED，无 ACTIVE 面）。</li>
 * </ul>
 *
 * <p>sourceDigest = 轨迹内容指纹（run id + 证据 canonical payload 全集 sha256）——
 * 同一源版本重复提交天然走 S14 幂等（不堆重复候选、生成成本不重复入账）；
 * 模型/生成消耗记账面归既有预算链（本服务零模型调用=确定性提炼）。
 */
public class SkillCuratorService {

    private static final Logger log = LoggerFactory.getLogger(SkillCuratorService.class);

    /** 成功工具调用读口（RcaToolInvocationLedger::findSuccessfulByRun 方法引用装配） */
    @FunctionalInterface
    public interface ToolSuccessSource {

        List<RcaToolInvocationLedger.InvocationRecovery> byRun(UUID runId);
    }

    private final RcaRunSource runs;
    private final EvidenceRepository evidence;
    private final ToolSuccessSource toolSuccesses;
    private final SkillCandidateService candidates;
    private final Clock clock;

    public SkillCuratorService(RcaRunSource runs, EvidenceRepository evidence,
            ToolSuccessSource toolSuccesses, SkillCandidateService candidates, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.toolSuccesses = Objects.requireNonNull(toolSuccesses, "toolSuccesses");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 提案请求（人工复核依据=humanReviewed 声明面，复核责任在调用方） */
    public record Curation(UUID runId, String name, String alertname, String service,
            boolean humanReviewed, String curatedBy) {
    }

    /** 结果：refusal 非 null = 来源契约拒绝（零落库）；否则候选行（DRAFT 或 S02 REJECTED） */
    public record Verdict(SkillCandidate candidate, String refusal) {

        public static Verdict refused(String reason) {
            return new Verdict(null, reason);
        }
    }

    public Verdict curate(Curation c) {
        requireNonBlank(c.name(), "name");
        requireNonBlank(c.curatedBy(), "curated_by");
        RcaRun run = runs.byId(c.runId()).orElse(null);
        if (run == null) {
            return Verdict.refused("来源契约：run " + c.runId() + " 不存在（轨迹不可溯源）");
        }
        if (run.state().isActive()) {
            return Verdict.refused("来源契约：run 尚未终态（" + run.state()
                    + "）——轨迹未封存，不生成候选");
        }
        if (run.state() == RcaRunState.FAILED) {
            return Verdict.refused("来源契约：FAILED 轨迹不作正向原料（失败经验沉淀归"
                    + " stop_conditions/反例面，不当成功故事）");
        }
        if (run.state() != RcaRunState.SUCCEEDED) {
            return Verdict.refused("来源契约：终态 " + run.state() + " 非合法封存源");
        }

        List<EvidenceEnvelope> rows = evidence.findByRunId(c.runId());
        if (rows.isEmpty()) {
            return Verdict.refused("来源契约：零证据轨迹不生成（无引用步骤=无证经验）");
        }

        // sourceDigest = 轨迹内容指纹（run id + 证据 payload 全集）——同源版本幂等锚
        StringBuilder fingerprint = new StringBuilder(c.runId().toString());
        rows.stream().sorted(java.util.Comparator.comparing(
                        e -> e.evidenceId().toString()))
                .forEach(e -> fingerprint.append('|').append(e.canonicalPayload()));
        String sourceDigest = Digest.sha256Of(fingerprint.toString()).value();

        // 提炼：证据类型时序去重 = 有界步骤（可审计动作面）；成功调用计数入正文
        Set<String> actionTypes = new LinkedHashSet<>();
        rows.forEach(e -> actionTypes.add(e.evidenceType()));
        long successCalls = toolSuccesses.byRun(c.runId()).size();

        String body = "来源：已封存调查轨迹 run=" + c.runId() + "（SUCCEEDED，"
                + rows.size() + " 条证据/" + successCalls + " 次成功工具调用）。"
                + "取证动作序列：" + String.join(" → ", actionTypes) + "。"
                + "适用边界：仅在该告警家族与服务范围内复用；历史结论≠当前根因，"
                + "与当前证据矛盾时保留反证；证据不足如实声明缺口。";

        SkillCandidateService.ProposeResult result = candidates.propose(
                new SkillCandidateService.Proposal(c.name(), c.runId(), sourceDigest,
                        c.humanReviewed(), c.alertname(), c.service(),
                        List.copyOf(actionTypes),
                        List.of("连续 3 步零新证据即停",
                                "证据不足时如实声明缺口，不得无引用下结论"),
                        List.copyOf(actionTypes), SkillCandidateService.MAX_TOOL_CALLS_CAP,
                        body, c.curatedBy()));
        log.info("Skill 源头提炼：run={} name={} → {}（verified={}）", c.runId(), c.name(),
                result.candidate().status(), c.humanReviewed());
        return new Verdict(result.candidate(), null);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }
}

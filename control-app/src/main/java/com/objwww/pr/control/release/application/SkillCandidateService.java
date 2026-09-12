package com.objwww.pr.control.release.application;

import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.model.ReleaseQualification;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.control.release.domain.repository.SkillCandidateRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Skill 候选服务（EN-08，增强线方案 §八拟议面）：封存轨迹→DRAFT→静态校验→独立
 * 评测→人工授权发布。生成器仅写 DRAFT（S01）；未复核原料落 REJECTED 隔离（S02）；
 * 提炼面确定性去事故特有 ID/答案标签并验泄漏（S03）；manifest 静态校验远端零调用
 * （S04 有界步骤/禁 DAG、S05 权限交集+预算上界）；资格门 = passQualified()
 * （S09：FAIL/INCONCLUSIVE 不晋升，MATCHED 不背书）；激活重算资产 digest 对账
 * （S10 篡改新身份）；幂等键重放（S14）。生产读面只出 ACTIVE（S08）。
 *
 * <p>自动生成器无晋升权限：本服务不提供任何绕过资格/人工授权的捷径；评测证明
 * 归 release_qualification 面登记，本服务只消费其 passQualified() 结论。
 */
public class SkillCandidateService {

    private static final Logger log = LoggerFactory.getLogger(SkillCandidateService.class);

    /** manifest 有界步骤上限（S04：超界/无限补证分支=校验失败） */
    static final int MAX_STEPS = 16;
    /** 单步骤文本上界（防整卷轨迹塞入步骤） */
    static final int STEP_CLIP = 200;
    /** 系统预算上界（S05：Skill 声明不得超出） */
    static final long MAX_TOOL_CALLS_CAP = 40;
    /** 提炼泄漏校验正则（S03：事故特有 ID/答案标签——候选必须零命中） */
    static final Pattern INCIDENT_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                    Pattern.CASE_INSENSITIVE);
    static final Pattern IP_LITERAL = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    static final Pattern ANSWER_LABEL =
            Pattern.compile("(?i)(golden[_-]?answer|answer[_-]?key|expected[_-]?root[_-]?cause)");

    private final SkillCandidateRepository candidates;
    private final ReleaseAssetRepository assets;
    private final Clock clock;

    public SkillCandidateService(SkillCandidateRepository candidates,
            ReleaseAssetRepository assets, Clock clock) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------ 提案面（S01/S02/S03/S14）

    /** 提案输入（生成器产物=确定性提炼结构；LLM 提炼不在首期——偏差登记） */
    public record Proposal(String name, UUID sourceRunId, String sourceDigest,
            boolean verified, String selectorAlertnames, String selectorServices,
            List<String> steps, List<String> stopConditions, List<String> tools,
            long toolCallsCap, String body, String proposedBy) {
    }

    /** 提案结果：dup=true = S14 幂等重放既有行（生成成本以既有 createdAt 审计） */
    public record ProposeResult(SkillCandidate candidate, boolean dup) {
    }

    public ProposeResult propose(Proposal p) {
        Objects.requireNonNull(p, "proposal");
        requireNonBlank(p.name(), "name");
        requireNonBlank(p.proposedBy(), "proposed_by");
        Instant now = clock.instant();

        // S14 幂等：同 (source_digest, name) 已有行 → 原样返回，不堆重复候选
        Optional<SkillCandidate> existing =
                candidates.findBySource(p.sourceDigest(), p.name());
        if (existing.isPresent()) {
            log.info("Skill 候选幂等重放（S14）：name={} source={}", p.name(),
                    p.sourceDigest().substring(0, 8));
            return new ProposeResult(existing.get(), true);
        }

        // S02 原料门：未复核轨迹不当可信正向原料——落 REJECTED 隔离行，不落资产
        SkillCandidate initial = new SkillCandidate(UUID.randomUUID(), p.name(),
                p.sourceRunId(), p.sourceDigest(),
                p.verified() ? SkillCandidate.VERIFIED : SkillCandidate.UNVERIFIED,
                null, SkillCandidate.ST_DRAFT, null, p.proposedBy(),
                null, null, null, null, null, now, now);
        if (!p.verified()) {
            SkillCandidate rejected = initial.reject(
                    "S02：原料未过人工复核（verification_status=UNVERIFIED），"
                            + "不当可信正向原料——隔离留痕，修复复核后重新提案", now);
            candidates.insert(rejected);
            return new ProposeResult(rejected, false);
        }

        // S03 提炼清洗：去事故特有 ID/IP/答案标签行 → 验泄漏（命中即拒绝整卷提案）
        List<String> steps = new ArrayList<>();
        for (String step : p.steps() == null ? List.<String>of() : p.steps()) {
            steps.add(scrub(step));
        }
        String body = scrub(p.body());
        for (Leak leak : java.util.Arrays.asList(leakOf(body, "body"),
                leakOf(String.join("\n", steps), "steps"))) {
            if (leak != null) {
                SkillCandidate rejected = initial.reject(
                        "S03：提炼产物泄漏 " + leak.field() + " 命中 " + leak.pattern()
                                + "——整卷拒绝，清洗后重新提案", now);
                candidates.insert(rejected);
                return new ProposeResult(rejected, false);
            }
        }

        // S01：落 DRAFT + SKILL 资产（内容寻址幂等），无任何 ACTIVE 面
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("alertnames", splitSelector(p.selectorAlertnames()));
        manifest.put("services", splitSelector(p.selectorServices()));
        manifest.put("steps", steps);
        manifest.put("stop_conditions", p.stopConditions() == null ? List.of() : p.stopConditions());
        manifest.put("tools", p.tools() == null ? List.of() : p.tools());
        manifest.put("budget_caps", Map.of("tool_calls", p.toolCallsCap()));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("body", body);
        content.put("manifest", manifest);
        content.put("source", Map.of("run_id", String.valueOf(p.sourceRunId()),
                "source_digest", p.sourceDigest(), "verified", true));
        ReleaseAsset asset = ReleaseAsset.of(ReleaseAsset.KIND_SKILL, content,
                p.proposedBy(), now);
        assets.insert(asset);

        SkillCandidate draft = initial.withAsset(asset.assetDigest().hex(), now);
        candidates.insert(draft);
        log.info("Skill 候选 DRAFT 落档（S01）：name={} asset={}，无任何 ACTIVE 面",
                p.name(), asset.assetDigest().hex().substring(0, 8));
        return new ProposeResult(draft, false);
    }

    // ------------------------------------------------------------ 校验面（S04/S05，远端零调用）

    /**
     * DRAFT→VALIDATING→EVALUATING（静态校验过）或 REJECTED（失败留痕）。
     * 纯结构面校验：零远端调用、零执行计数（S04"远端执行计数零"）。
     * @param allowedTools 系统策略∩角色权限的工具全集（S05 权限交集的另两维由调用方给）
     */
    public SkillCandidate validate(UUID candidateId, java.util.Set<String> allowedTools) {
        SkillCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        Instant now = clock.instant();
        SkillCandidate validating = candidate.startValidation(now);
        candidates.update(validating);

        String failure = validateManifest(assetManifest(validating.assetDigest()),
                allowedTools);
        if (failure != null) {
            SkillCandidate rejected = validating.reject(failure, now);
            candidates.update(rejected);
            log.info("Skill 候选校验拒绝（远端零调用）：{} —— {}", candidate.name(), failure);
            return rejected;
        }
        SkillCandidate evaluating = validating.passValidation(now);
        candidates.update(evaluating);
        return evaluating;
    }

    /** manifest 静态校验：null = 过；非 null = 拒绝原因（S04/S05） */
    String validateManifest(Map<String, Object> manifest,
            java.util.Set<String> allowedTools) {
        if (manifest == null) {
            return "S04：SKILL 资产缺 manifest（ReleaseAsset 契约外形状）";
        }
        Object stepsRaw = manifest.get("steps");
        if (!(stepsRaw instanceof List<?> steps) || steps.isEmpty()) {
            return "S04：steps 必为非空列表（有界步骤）";
        }
        if (steps.size() > MAX_STEPS) {
            return "S04：steps 超有界上限 " + MAX_STEPS + "（超深 DAG/无限补证分支即拒）";
        }
        if (manifest.containsKey("dag") || manifest.containsKey("branches")) {
            return "S04：首期 manifest 不接受 DAG/分支结构（有界线性步骤）";
        }
        Object stop = manifest.get("stop_conditions");
        if (!(stop instanceof List<?> stops) || stops.isEmpty()) {
            return "S04：stop_conditions 必为非空列表（停止条件必声明）";
        }
        Object caps = manifest.get("budget_caps");
        if (caps instanceof Map<?, ?> budget) {
            Object toolCalls = budget.get("tool_calls");
            if (toolCalls instanceof Number n && n.longValue() > MAX_TOOL_CALLS_CAP) {
                return "S05：预算声明超系统上界（tool_calls " + n.longValue()
                        + " > " + MAX_TOOL_CALLS_CAP + "）——不能提升额度";
            }
        } else {
            return "S04：budget_caps 必为映射";
        }
        Object toolsRaw = manifest.get("tools");
        if (!(toolsRaw instanceof List<?> tools)) {
            return "S04：tools 必为列表（权限交集声明面）";
        }
        for (Object tool : tools) {
            if (allowedTools == null || !allowedTools.contains(String.valueOf(tool))) {
                return "S05：Skill 声明工具不在授权交集内（不能扩权/触发副作用）: " + tool;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 资格与发布面（S08/S09/S10/S12）

    /**
     * EVALUATING→QUALIFIED（仅 PASS 且未撤销）或 REJECTED（FAIL/INCONCLUSIVE 留痕；
     * S09：MATCHED 只对账不背书，费用 MATCHED 也不能绕质量门）。
     */
    public SkillCandidate recordQualification(UUID candidateId,
            ReleaseQualification qualification) {
        Objects.requireNonNull(qualification, "qualification");
        SkillCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        Instant now = clock.instant();
        if (qualification.passQualified()) {
            SkillCandidate qualified = candidate.qualify(now);
            candidates.update(qualified);
            return qualified;
        }
        SkillCandidate rejected = candidate.reject(
                "S09：评测结论 " + qualification.qualityVerdict()
                        + " 不能晋升（INCONCLUSIVE 属评测结论同样不能晋升；MATCHED 只对账）",
                now);
        candidates.update(rejected);
        return rejected;
    }

    /**
     * QUALIFIED→ACTIVE（人工授权发布；S10：重读资产重算 digest 对账——篡改正文=
     * 新 digest=候选指针不变=拒绝激活；旧证明不能背书新内容）。
     */
    public SkillCandidate activate(UUID candidateId, String activatedBy) {
        requireNonBlank(activatedBy, "activated_by（人工授权面）");
        SkillCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        Instant now = clock.instant();
        Digest stored = new Digest(Objects.requireNonNull(candidate.assetDigest(),
                "候选无资产指针（未落 DRAFT），不得激活"));
        ReleaseAsset asset = assets.findByDigest(ReleaseAsset.KIND_SKILL, stored)
                .orElseThrow(() -> new IllegalStateException(
                        "S10：候选指向的 SKILL 资产缺席（" + stored.hex() + "）"));
        Digest recomputed = Digest.sha256Of(
                com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                        .canonicalize(asset.content()));
        if (!recomputed.equals(stored)) {
            throw new IllegalStateException(
                    "S10：资产内容重算 digest 与候选指针不匹配——旧证明不能背书新内容，激活拒绝");
        }
        SkillCandidate active = candidate.activate(activatedBy, now);
        candidates.update(active);
        log.info("Skill 候选人工授权激活：name={} by={} asset={}", candidate.name(),
                activatedBy, stored.hex().substring(0, 8));
        return active;
    }

    /**
     * 退场面（S12 语义分开）：emergency=true → ACTIVE 直退 RETIRED（紧急撤销）；
     * emergency=false → ACTIVE 停在 DEPRECATED（第一步），对 DEPRECATED 再调一次
     * 才 RETIRED（两步确认的普通退场）。
     */
    public SkillCandidate retire(UUID candidateId, String by, String reason,
            boolean emergency) {
        SkillCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        Instant now = clock.instant();
        if (SkillCandidate.ST_ACTIVE.equals(candidate.status())) {
            SkillCandidate moved = emergency
                    ? candidate.retire(by, reason, now)
                    : candidate.deprecate(now);
            candidates.update(moved);
            return moved;
        }
        if (SkillCandidate.ST_DEPRECATED.equals(candidate.status()) && !emergency) {
            SkillCandidate retired = candidate.retire(by, reason, now);
            candidates.update(retired);
            return retired;
        }
        throw new IllegalStateException("S12：退场面非法起点 " + candidate.status()
                + "（紧急撤销仅限 ACTIVE；普通退场=ACTIVE→DEPRECATED→RETIRED 两步）");
    }

    /** 生产读面（S08）：只出 ACTIVE——EVALUATING/QUALIFIED 候选对生产 Run 不可见 */
    public List<SkillCandidate> activeSkills() {
        return candidates.listByStatus(SkillCandidate.ST_ACTIVE);
    }

    // ------------------------------------------------------------ 提炼清洗（S03）

    /** 确定性清洗：UUID/IP 字面量泛化 + 答案标签行剔除 */
    static String scrub(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (ANSWER_LABEL.matcher(line).find()) {
                continue;
            }
            out.append(IP_LITERAL.matcher(
                    INCIDENT_ID.matcher(line).replaceAll("<事故特有id已脱敏>"))
                    .replaceAll("<ip已脱敏>")).append('\n');
        }
        return out.toString().strip();
    }

    record Leak(String field, String pattern) {
    }

    /** 泄漏验面：清洗后仍命中任一形状 = 提炼失败（整卷拒绝，S03"移除特例且验泄漏"） */
    static Leak leakOf(String text, String field) {
        if (INCIDENT_ID.matcher(text).find()) {
            return new Leak(field, "incident-uuid");
        }
        if (IP_LITERAL.matcher(text).find()) {
            return new Leak(field, "ip-literal");
        }
        if (ANSWER_LABEL.matcher(text).find()) {
            return new Leak(field, "answer-label");
        }
        return null;
    }

    /** selector 逗号清单 → trim 后列表（空=通配） */
    private static List<String> splitSelector(String list) {
        if (list == null || list.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(list.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 读候选资产的 manifest 段（缺资产=校验失败面） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> assetManifest(String assetDigest) {
        if (assetDigest == null) {
            return null;
        }
        return assets.findByDigest(ReleaseAsset.KIND_SKILL, new Digest(assetDigest))
                .map(asset -> {
                    Object manifest = asset.content().get("manifest");
                    return manifest instanceof Map<?, ?> m
                            ? new LinkedHashMap<String, Object>((Map<String, Object>) m)
                            : null;
                })
                .orElse(null);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为 blank");
        }
    }
}

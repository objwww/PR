package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.claim.ClaimKind;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 主 FINAL 提案 → Claim 投影（R7-X6 装配缝）：{@link BoundedLlmRoleRunner} 的 FINAL
 * 经 {@link PrimaryClaimAdmission} 代码准入后落主任务检查点（final_claims jsonb），
 * 而报告相位消费面（ReportAssembler/ClaimStore）只认 rca_claim 投影——本类把检查点
 * 提案确定性投影进 {@link ClaimStore}（append 四分支幂等：同指纹同内容 = UNCHANGED，
 * 恢复重驱不产生重复断言）。
 *
 * <p>映射 v2（A0 补充方案 §2 第 5 条——引用存在≠证据支持，投影消费准入判定，
 * 不再仅从 kind 推 TRUE、从来源数量推一致）：
 * <ul>
 *   <li>kind=ROOT_CAUSE 且该行存在准入确认的 SUPPORTS 判定 → TRUE；否则 UNKNOWN
 *       （未确认支持不确认结论——旧 v1 的 kind→TRUE 直推废止）；</li>
 *   <li>kind=EXCLUSION 且存在 REFUTES 判定 → FALSE（证伪也是"确定"）；否则
 *       UNKNOWN；</li>
 *   <li>同行并存 SUPPORTS 与 REFUTES → 自相矛盾，UNKNOWN（对峙呈堂同族）；</li>
 *   <li>其余（HYPOTHESIS/SYMPTOM）→ UNKNOWN（未决节）。</li>
 * </ul>
 * basis 的来源计数只认与结论方向一致的判定（TRUE 看 SUPPORTS、FALSE 看
 * REFUTES）——CONTEXT/未确认引用的来源不计入，不机械凑 MULTI_SOURCE_CONSISTENT
 * （AS-06：无关背景日志不能变成第二份独立支持；同底层信号多工具读取按 source
 * 标签归并，不按 toolId 伪造独立性）。单一权威证据支持有限结论仍是合法结果
 * （SINGLE_SOURCE TRUE 只进推测节，不冒充确认）。
 * 零引用行跳过（无证据不成断言——ClaimVerdict 构造期硬约束；降级行留在检查点
 * 审计面，不静默丢，WARN 留痕）。sources 从<b>方向一致判定</b>引用的证据行来源
 * 标签推导去重；无 evidence_roles 的旧检查点行（v1 时代）按全部未确认处理=UNKNOWN。
 *
 * @author wanghua
 * @date 2026-09-11
 */
public final class PrimaryFinalClaimProjector {

    private static final Logger log = LoggerFactory.getLogger(PrimaryFinalClaimProjector.class);

    /** 投影身份面：scope 固定 primary（来源区分主 Agent 直查断言与证据推导断言） */
    public static final String SCOPE = "primary";
    /** 投影策略版本 v2（A0 补充方案 §2 第 6 条：支持判定面升版——新旧结果在评测中
     * 分开，不批量回写旧报告为新策略通过） */
    public static final String POLICY_VERSION = "r7-primary-v2";

    private final ClaimStore claims;
    private final EvidenceRepository evidence;

    public PrimaryFinalClaimProjector(ClaimStore claims, EvidenceRepository evidence) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * 检查点 FINAL 提案逐组投影；返回实际 append 的行数（跳过行不计，同 claimKey
     * 组恰一行）。
     *
     * <p>MC22/P0-1 对峙呈堂：投影前按 claimKey 分组做状态冲突检测——同组 TRUE/FALSE
     * 双断言若各自落行，ClaimStore 同指纹异内容 = REVISED 覆盖（反证被覆盖而非对峙
     * 呈堂，MC22 禁面）；冲突组强制 UNKNOWN + MULTI_SOURCE_CONFLICT 单行，证据引用
     * 与来源为全组并集（与 {@code ClaimReducer.reduceGroup} 无法裁决分支同语义）。
     * 同组同态多行 = 同一断言的重复提交，合并引用单行投影（避免 REVISED 覆盖同族
     * 漂移）。EXCLUSION→FALSE 的"确认排除"语义不变；kind 语义门归 R7c P1-01，
     * 冲突/合并行的 kind 取首行（确定性，投影序 = 提案行序）。
     *
     * @param snapshotHex 本 run 冻结证据快照 digest（外来快照排除面依赖此值对齐）
     */
    public int project(UUID runId, PrimaryCheckpoint checkpoint, String snapshotHex,
            long generation, String timeRange) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : checkpoint.finalClaims()) {
            String claimKey = str(row.get("claim_key"));
            String statement = str(row.get("statement"));
            List<String> refs = strings(row.get("evidence_refs"));
            if (claimKey == null || statement == null) {
                log.warn("主 FINAL 提案行缺 claim_key/statement，跳过投影 run={} row={}",
                        runId, claimKey);
                continue;
            }
            if (refs.isEmpty()) {
                log.warn("主 FINAL 提案零证据引用，不成断言（留检查点审计面）run={} claim={}",
                        runId, claimKey);
                continue;
            }
            groups.computeIfAbsent(claimKey, key -> new ArrayList<>()).add(row);
        }
        int appended = 0;
        for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
            if (projectGroup(runId, group.getKey(), group.getValue(), snapshotHex,
                    generation, timeRange)) {
                appended++;
            }
        }
        return appended;
    }

    /** 同 claimKey 组投影恰一行：状态冲突 → UNKNOWN+CONFLICT 并集呈堂；否则按准入判定 */
    private boolean projectGroup(UUID runId, String claimKey,
            List<Map<String, Object>> rows, String snapshotHex, long generation,
            String timeRange) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        LinkedHashSet<ClaimStatus> statuses = new LinkedHashSet<>();
        LinkedHashSet<ClaimKind> kinds = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        // 方向一致判定的引用集（TRUE→SUPPORTS / FALSE→REFUTES）——basis 来源唯一依据
        LinkedHashSet<String> directionRefs = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            List<String> rowRefs = strings(row.get("evidence_refs"));
            refs.addAll(rowRefs);
            Map<String, String> roles = rolesOf(row);
            boolean supports = false;
            boolean refutes = false;
            for (String ref : rowRefs) {
                String role = roles.getOrDefault(ref, "CONTEXT");
                supports |= role.equals("SUPPORTS");
                refutes |= role.equals("REFUTES");
            }
            ClaimKind kind = parseKind(str(row.get("kind")));
            statuses.add(statusFor(kind, supports, refutes));
            kinds.add(kind);
            if (statuses.size() <= 1) {
                // 组内尚无冲突：按结论方向收集支持来源引用（冲突组不收集，呈堂并集）
                String direction = statuses.iterator().next() == ClaimStatus.FALSE
                        ? "REFUTES" : "SUPPORTS";
                for (String ref : rowRefs) {
                    if (roles.getOrDefault(ref, "CONTEXT").equals(direction)) {
                        directionRefs.add(ref);
                    }
                }
            }
            String note = str(row.get("admission_note"));
            String statement = str(row.get("statement"));
            String verdictFace = verdictFace(rowRefs, roles);
            reasons.add((note == null ? statement : statement + " [" + note + "]")
                    + (verdictFace.isEmpty() ? "" : " {" + verdictFace + "}"));
        }

        ClaimStatus status;
        EvidenceBasis basis;
        String reason;
        List<String> supportSources;
        if (statuses.size() > 1) {
            status = ClaimStatus.UNKNOWN;
            basis = EvidenceBasis.MULTI_SOURCE_CONFLICT;
            supportSources = List.of();
            reason = "对峙呈堂（同 claimKey 状态冲突，反证不覆盖）："
                    + String.join("；", reasons);
            log.warn("主 FINAL 提案同 claimKey 状态冲突，强制 UNKNOWN+CONFLICT 并集呈堂 "
                    + "run={} claim={} statuses={}", runId, claimKey, statuses);
        } else {
            status = statuses.iterator().next();
            // 来源 = 方向一致判定的引用所锚证据行的 source 标签（同底层信号归并；
            // CONTEXT/未确认引用不进集——不按 toolId 数量伪造独立性）
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            for (String ref : directionRefs) {
                try {
                    evidence.findById(UUID.fromString(ref))
                            .ifPresent(e -> sources.add(e.source()));
                } catch (IllegalArgumentException notUuid) {
                    // 非 UUID 引用（编译期 artifact 键）不参与来源推导，引用本身保留
                }
            }
            supportSources = List.copyOf(sources);
            basis = sources.size() >= 2
                    ? EvidenceBasis.MULTI_SOURCE_CONSISTENT
                    : EvidenceBasis.SINGLE_SOURCE;
            reason = reasons.size() == 1 ? reasons.get(0)
                    : String.join("；", reasons.stream().distinct().toList());
        }
        claims.append(runId, new ClaimVerdict(claimKey, SCOPE, timeRange, generation,
                snapshotHex, status, basis, supportSources,
                reason, List.copyOf(refs), POLICY_VERSION, kinds.iterator().next(),
                rootCauseOf(runId, claimKey, rows), symptomCodesOf(rows)));
        return true;
    }

    /**
     * 症状码投影（V151）：取组内首个声明 symptom_codes 的提案行（与 root_cause/
     * kind 取首行同律——确定性，投影序=提案序）；组内全缺该键 → null 未声明
     * （报告面 symptom_codes 诚实空数组，不拿证据来源标签冒充——BA-158 同族实证
     * 批 aa7f25b4 tp=0/fp=99/fn=60）。本面诚实透传不做词表过滤（规训归协议面）。
     */
    private static List<String> symptomCodesOf(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (row.get("symptom_codes") instanceof List<?>) {
                return strings(row.get("symptom_codes"));
            }
        }
        return null;
    }

    /**
     * 结构化根因三元组投影（V147）：取组内首个带合法 root_cause 的提案行
     * （与 kind 取首行同律——确定性，投影序=提案序）；行缺该键/形状非法 → null
     * 诚实降级（报告面落 unknown 三元组，不拿 scope/claimKey 冒充），非法形状
     * WARN 留痕不静默吞。
     */
    private static com.objwww.pr.control.alert.domain.model.TypedRootCause rootCauseOf(
            UUID runId, String claimKey, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (!(row.get("root_cause") instanceof Map<?, ?> rc)) {
                continue;
            }
            try {
                return new com.objwww.pr.control.alert.domain.model.TypedRootCause(
                        str(rc.get("component")), str(rc.get("fault_type")),
                        str(rc.get("reason_code")));
            } catch (IllegalArgumentException | NullPointerException malformed) {
                log.warn("主 FINAL 提案 root_cause 三元组非法，诚实降级 null "
                        + "run={} claim={} cause={}", runId, claimKey,
                        malformed.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * v2 状态判定（A0 补充方案 §2 第 5 条）：消费准入判定，不再 kind→TRUE 直推——
     * ROOT_CAUSE 需存在 SUPPORTS 判定才 TRUE；EXCLUSION 需 REFUTES 才 FALSE；
     * 同行并存 SUPPORTS+REFUTES=自相矛盾 UNKNOWN；未确认支持一律 UNKNOWN。
     */
    private static ClaimStatus statusFor(ClaimKind kind, boolean supports,
            boolean refutes) {
        if (supports && refutes) {
            return ClaimStatus.UNKNOWN; // 同一断言内自相矛盾（对峙呈堂同族）
        }
        return switch (kind) {
            case ROOT_CAUSE -> supports ? ClaimStatus.TRUE : ClaimStatus.UNKNOWN;
            case EXCLUSION -> refutes ? ClaimStatus.FALSE : ClaimStatus.UNKNOWN;
            default -> ClaimStatus.UNKNOWN;
        };
    }

    /** 行内 evidence_roles → ref→role（缺省 CONTEXT：v1 旧行=全部未确认） */
    private static Map<String, String> rolesOf(Map<String, Object> row) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(row.get("evidence_roles") instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> rv
                    && rv.get("ref") instanceof String ref
                    && rv.get("role") instanceof String role) {
                out.putIfAbsent(ref, role);
            }
        }
        return out;
    }

    /** 判定留痕（投影 reason 面，审计可账）：ref:ROLE 紧凑连接 */
    private static String verdictFace(List<String> refs, Map<String, String> roles) {
        if (roles.isEmpty()) {
            return "";
        }
        return refs.stream().map(ref -> ref + ":" + roles.getOrDefault(ref, "CONTEXT"))
                .reduce((a, b) -> a + "," + b).orElse("");
    }

    private static ClaimKind parseKind(String raw) {
        if (raw == null) {
            return ClaimKind.HYPOTHESIS;
        }
        try {
            return ClaimKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ClaimKind.HYPOTHESIS;
        }
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }
}

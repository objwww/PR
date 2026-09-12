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
 * <p>映射（X6 装配决策，如实在案；类型语义门归 R7c P1-01，接手后可原地替换）：
 * <ul>
 *   <li>kind=ROOT_CAUSE → TRUE（≥2 独立来源 = MULTI_SOURCE_CONSISTENT 进确认节；
 *       单源 = SINGLE_SOURCE——单源 TRUE 只进推测节，不冒充确认）；</li>
 *   <li>kind=EXCLUSION → FALSE（证伪也是"确定"）；</li>
 *   <li>其余（HYPOTHESIS/SYMPTOM）→ UNKNOWN（未决节）。</li>
 * </ul>
 * 零引用行跳过（无证据不成断言——ClaimVerdict 构造期硬约束；降级行留在检查点
 * 审计面，不静默丢，WARN 留痕）。sources 从引用证据行的来源标签推导去重。
 *
 * @author wanghua
 * @date 2026-09-11
 */
public final class PrimaryFinalClaimProjector {

    private static final Logger log = LoggerFactory.getLogger(PrimaryFinalClaimProjector.class);

    /** 投影身份面：scope 固定 primary（来源区分主 Agent 直查断言与证据推导断言） */
    public static final String SCOPE = "primary";
    /** X6 投影策略版本（contentHash 组成面；R7c 接手类型门时升版） */
    public static final String POLICY_VERSION = "r7-primary-v1";

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

    /** 同 claimKey 组投影恰一行：状态冲突 → UNKNOWN+CONFLICT 并集呈堂；否则按态 */
    private boolean projectGroup(UUID runId, String claimKey,
            List<Map<String, Object>> rows, String snapshotHex, long generation,
            String timeRange) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        LinkedHashSet<ClaimStatus> statuses = new LinkedHashSet<>();
        LinkedHashSet<ClaimKind> kinds = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> rowRefs = strings(row.get("evidence_refs"));
            refs.addAll(rowRefs);
            for (String ref : rowRefs) {
                try {
                    evidence.findById(UUID.fromString(ref))
                            .ifPresent(e -> sources.add(e.source()));
                } catch (IllegalArgumentException notUuid) {
                    // 非 UUID 引用（编译期 artifact 键）不参与来源推导，引用本身保留
                }
            }
            ClaimKind kind = parseKind(str(row.get("kind")));
            statuses.add(statusFor(kind));
            kinds.add(kind);
            String note = str(row.get("admission_note"));
            String statement = str(row.get("statement"));
            reasons.add(note == null ? statement : statement + " [" + note + "]");
        }

        ClaimStatus status;
        EvidenceBasis basis;
        String reason;
        if (statuses.size() > 1) {
            status = ClaimStatus.UNKNOWN;
            basis = EvidenceBasis.MULTI_SOURCE_CONFLICT;
            reason = "对峙呈堂（同 claimKey 状态冲突，反证不覆盖）："
                    + String.join("；", reasons);
            log.warn("主 FINAL 提案同 claimKey 状态冲突，强制 UNKNOWN+CONFLICT 并集呈堂 "
                    + "run={} claim={} statuses={}", runId, claimKey, statuses);
        } else {
            status = statuses.iterator().next();
            basis = sources.size() >= 2
                    ? EvidenceBasis.MULTI_SOURCE_CONSISTENT
                    : EvidenceBasis.SINGLE_SOURCE;
            reason = reasons.size() == 1 ? reasons.get(0)
                    : String.join("；", reasons.stream().distinct().toList());
        }
        claims.append(runId, new ClaimVerdict(claimKey, SCOPE, timeRange, generation,
                snapshotHex, status, basis, List.copyOf(sources), reason,
                List.copyOf(refs), POLICY_VERSION, kinds.iterator().next()));
        return true;
    }

    private static ClaimStatus statusFor(ClaimKind kind) {
        return switch (kind) {
            case ROOT_CAUSE -> ClaimStatus.TRUE;
            case EXCLUSION -> ClaimStatus.FALSE;
            default -> ClaimStatus.UNKNOWN;
        };
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

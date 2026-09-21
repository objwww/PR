package com.objwww.pr.control.alert.application.approval;

import com.objwww.pr.control.alert.application.mutation.ActionIntentStore;
import com.objwww.pr.control.alert.domain.approval.GuardianVerdict;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Guardian 预审（Phase E，设计基线 §2.3/§2.0）：确定性规则裁决——
 * <ul>
 *   <li><b>封闭三值</b>：SAFE（低危白名单工具 + 参数在预算内）→ 自动签发；
 *       UNSAFE（参数超限）→ 自动拒绝；<b>UNCERTAIN → 转人工</b>（工具不在
 *       低危白名单/解析失败，绝不猜 SAFE）；</li>
 *   <li><b>权限单调</b>：SAFE 无升级放行权——R3/高危永远人工双人；Guardian 的
 *       自动批准以 {@code guardian:&lt;policyVersion&gt;} 作为 approver 落
 *       decisions 表（机器审批显式可见，审计面不区分对待）；</li>
 *   <li><b>Hardline 先于一切</b>：命中绝对禁止清单的工具不进 Guardian——
 *       直接阻断 + 审计（消费模板 HARDLINE 拒绝，双层）。</li>
 * </ul>
 */
public class MutationGuardian {

    public record Review(GuardianVerdict verdict, String reason) {
    }

    private final ActionIntentStore intents;
    private final RcaEventAppender events;
    private final TransactionOperations tx;
    private final Set<String> lowRiskTools;
    private final int maxParamChars;
    private final String policyVersion;
    private final Clock clock;

    public MutationGuardian(ActionIntentStore intents, RcaEventAppender events,
            TransactionOperations tx, Set<String> lowRiskTools, int maxParamChars,
            String policyVersion, Clock clock) {
        this.intents = Objects.requireNonNull(intents);
        this.events = Objects.requireNonNull(events);
        this.tx = Objects.requireNonNull(tx);
        this.lowRiskTools = Set.copyOf(lowRiskTools);
        if (maxParamChars < 1) {
            throw new IllegalArgumentException("maxParamChars 必须为正");
        }
        this.maxParamChars = maxParamChars;
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.clock = Objects.requireNonNull(clock);
    }

    public String guardianApproverId() {
        return "guardian:" + policyVersion;
    }

    /** 确定性裁决 + GUARDIAN_REVIEWED 事件留痕（run 事件账本，同事务） */
    public Review review(UUID intentId) {
        return tx.execute(status -> {
            ActionIntentStore.IntentView intent = intents.findById(intentId)
                    .orElseThrow(() -> new IllegalArgumentException("意图不存在: " + intentId));
            Review review = evaluate(intent);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", "GUARDIAN_REVIEWED");
            payload.put("intent_id", intentId.toString());
            payload.put("verdict", review.verdict().name());
            payload.put("reason", review.reason());
            payload.put("policy_version", policyVersion);
            events.append(intent.runId(), new RcaEventAppender.EventDraft(UUID.randomUUID(),
                    "GUARDIAN_REVIEWED",
                    com.objwww.pr.control.alert.application.mutation.OperationPlanner
                            .CanonicalEventJson.canonicalize(payload)));
            return review;
        });
    }

    /** 确定性规则（纯函数语义；IntentView 入参便于穷举单测） */
    Review evaluate(ActionIntentStore.IntentView intent) {
        // 权限单调第一律：R3/高危 Guardian 无放行权——直接 UNCERTAIN 转人工
        if ("R3".equals(intent.risk())) {
            return new Review(GuardianVerdict.UNCERTAIN,
                    "RISK_MONOTONICITY: R3 永远人工双人，Guardian 无升级放行权");
        }
        if (!lowRiskTools.contains(intent.toolName())) {
            return new Review(GuardianVerdict.UNCERTAIN,
                    "TOOL_NOT_IN_LOW_RISK_ALLOWLIST: " + intent.toolName());
        }
        String args = intent.argsJson();
        if (args == null || args.isBlank()) {
            return new Review(GuardianVerdict.UNCERTAIN, "PARAMS_UNPARSEABLE: 空 params");
        }
        if (args.length() > maxParamChars) {
            return new Review(GuardianVerdict.UNSAFE,
                    "PARAMS_OVER_BUDGET: " + args.length() + " > " + maxParamChars);
        }
        // 低危白名单 + 参数在预算内 = SAFE（封闭规则，无模型参与——确定性裁决）
        return new Review(GuardianVerdict.SAFE, "LOW_RISK_ALLOWLIST + PARAMS_IN_BUDGET");
    }
}

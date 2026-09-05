package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.InvestigationResult;
import com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository;
import com.objwww.pr.control.eval.domain.litellm.LiteLlmAdminPort;
import com.objwww.pr.control.eval.domain.litellm.SpendRecord;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.AttemptUsage;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.RunLedgerInput;
import com.objwww.pr.control.eval.domain.litellm.UsageLedgerReconciler.UsageLedger;
import com.objwww.pr.control.eval.domain.repository.EvalRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * usage 对账编排（M3-25 出账口）：EvalRun → 关联的全部 RCA Run → 全部 attempt 账本
 * 用量（rca_investigation_result.usage_json，§6.2 usage_missing=NULL）→ proxy 行全集
 * 交给纯对账器（降级链在域内）。
 *
 * <p>降级原则（评审 P0-8 + spike 结论）：对账失败/未配置绝不阻断批件终态，也绝不
 * 伪造 MATCHED——统一落 UNMATCHED/BEST_EFFORT 台账并在 notes/日志留原因。
 * proxy spend 日志 flush 异步（spike 实测 ~16s），waitMillis 允许出账前等待。
 */
public final class UsageLedgerService {

    private static final Logger log = LoggerFactory.getLogger(UsageLedgerService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EvalRunRepository evalRuns;
    private final InvestigationResultRepository investigations;
    private final LiteLlmAdminPort litellm;   // null = 未配置（chain③ 诚实降级）
    private final String runKeyAlias;         // null = 无 per-run key（chain③）
    private final long waitMillis;

    public UsageLedgerService(EvalRunRepository evalRuns,
                              InvestigationResultRepository investigations,
                              LiteLlmAdminPort litellm,
                              String runKeyAlias,
                              long waitMillis) {
        this.evalRuns = evalRuns;
        this.investigations = investigations;
        this.litellm = litellm;
        this.runKeyAlias = (runKeyAlias == null || runKeyAlias.isBlank()) ? null : runKeyAlias;
        this.waitMillis = Math.max(0, waitMillis);
    }

    /** EvalRun 级三态出账（不抛异常：对账面失败落 UNMATCHED 台账，批件终态不受影响） */
    public UsageLedger reconcileEvalRun(UUID evalRunId) {
        RunLedgerInput input = buildInput(evalRunId);
        if (litellm == null) {
            log.warn("LiteLLM 未配置（app.alert.eval.litellm.base-url 为空），"
                    + "usage 对账按 chain③ 降级 UNMATCHED/BEST_EFFORT");
            return new UsageLedgerReconciler().reconcile(input, List.of());
        }
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<SpendRecord> rows;
        try {
            rows = litellm.spendLogs();
        } catch (RuntimeException e) {
            log.warn("SpendLogs 查询失败，usage 对账降级 UNMATCHED（run={}）: {}",
                    evalRunId, e.getMessage());
            return new UsageLedgerReconciler().reconcile(input, List.of());
        }
        log.warn("usage 对账: run={} 取得 proxy 行数={}", evalRunId, rows.size());
        return new UsageLedgerReconciler().reconcile(input, rows);
    }

    /** 关联全部 case 的全部 attempt 用量（评分选定的 report 只是子集——对账对全部尝试负责） */
    private RunLedgerInput buildInput(UUID evalRunId) {
        Set<UUID> rcaRunIds = new LinkedHashSet<>();
        evalRuns.findCasesByRunId(evalRunId).forEach(c -> {
            if (c.rcaRunId() != null) {
                rcaRunIds.add(c.rcaRunId());
            }
        });
        List<AttemptUsage> attempts = new ArrayList<>();
        for (UUID rcaRunId : rcaRunIds) {
            for (InvestigationResult result : investigations.findByRunId(rcaRunId)) {
                attempts.add(toAttemptUsage(result));
            }
        }
        return new RunLedgerInput(evalRunId, runKeyAlias, attempts);
    }

    /** usage_json（{"prompt_tokens":N,...} 或 NULL）→ AttemptUsage；解析失败按缺失落账 */
    private static AttemptUsage toAttemptUsage(InvestigationResult result) {
        if (result.usageJson() == null || result.usageJson().isBlank()) {
            return new AttemptUsage(result.attemptId(), null, null);
        }
        try {
            JsonNode usage = JSON.readTree(result.usageJson());
            JsonNode prompt = usage.path("prompt_tokens");
            JsonNode completion = usage.path("completion_tokens");
            if (!prompt.isInt() || !completion.isInt()) {
                return new AttemptUsage(result.attemptId(), null, null);
            }
            return new AttemptUsage(result.attemptId(), prompt.asInt(), completion.asInt());
        } catch (Exception e) {
            log.warn("usage_json 解析失败按 usage_missing 落账: attempt={}",
                    result.attemptId());
            return new AttemptUsage(result.attemptId(), null, null);
        }
    }
}

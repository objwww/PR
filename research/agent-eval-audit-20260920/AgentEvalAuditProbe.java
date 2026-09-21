package com.objwww.pr.control.alert.application.mcp;

import com.objwww.pr.control.alert.domain.budget.DoomLoopGuard;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.eval.domain.model.*;
import com.objwww.pr.control.eval.domain.service.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/** Audit characterization only: OBSERVED means a gap reproduced, not a quality pass. */
public class AgentEvalAuditProbe {
    public static void main(String[] args) {
        structuredOnlyMcpResult();
        missingUsageGate();
        noProgressBoundaries();
    }

    private static void structuredOnlyMcpResult() {
        var client = new McpTestFixtures.FakeClient(McpTestFixtures.tool("query"));
        var factory = new McpTestFixtures.FakeFactory();
        factory.suppliers.put("audit", () -> client);
        var manager = new McpMountManager(new McpTestFixtures.MemRegistry(), factory,
                Clock.systemUTC(), Duration.ofMinutes(5), 2, Set.of());
        // Fake factory: this address is never contacted.
        manager.register("audit", "streamable_http", "http://127.0.0.1:9/mcp", List.of(), null);
        var ledger = new McpTestFixtures.MemLedger();
        var invoker = new McpToolInvoker(manager, ledger, 4096);
        var structuredOnly = new McpServerClient.McpToolResult(false, null, true);
        check(!structuredOnly.malformed(), "structured-only result must pass current shape guard");
        client.callResults.add(structuredOnly);
        try {
            invoker.invoke("audit", "query", Map.of(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), 1);
            throw new AssertionError("Expected current structured-only gap; re-audit if fixed");
        } catch (ToolModelVisibleException ex) {
            check(ex.getMessage().contains("NullPointerException"), "unexpected MCP failure");
            System.out.println("OBSERVED MCP_STRUCTURED_ONLY: wrapped=REMOTE_UNAVAILABLE(NullPointerException)"
                    + "; ledger=" + ledger.rows.get(0).state()
                    + "/" + ledger.rows.get(0).reason());
        }
    }

    private static <T> SixDimResult.Dim<T> dim(T value) {
        return new SixDimResult.Dim<>(value, List.of());
    }

    private static void missingUsageGate() {
        var aggregate = new SixDimResult(
                dim(new DimensionCounts.Result(1, 0, 0, 1, true, false, false)),
                dim(new DimensionCounts.Process(1, 0, 0)),
                dim(new DimensionCounts.Tool(1, 0, 0)),
                dim(new DimensionCounts.Cost(100, 0, 0, 0, true)),
                dim(new DimensionCounts.Collaboration(1, 0, 0)),
                dim(new DimensionCounts.Safety(0, false)));
        var pairs = IntStream.range(0, 5).mapToObj(i ->
                new PairedTrialStats.PairedOutcome("cluster-" + i, true, true)).toList();
        var decision = new QualityGate().evaluate(
                new SafetyGate.SafetyVerdict(List.of(), SafetyGate.Verdict.PASS),
                PairedTrialStats.pairedDifference(pairs, 42), aggregate,
                new GateThresholds("audit", 0.02, 5, 1000, 1000, 0.1));
        check(decision.outcome() == EvaluationRecordV1.Outcome.ELIGIBLE_FOR_CANARY,
                "Expected current missing-usage gap; re-audit if fixed");
        System.out.println("OBSERVED USAGE_MISSING_GATE: usageMissing=true -> " + decision.outcome()
                + " (isolated QualityGate, not proof of production admission)");
    }

    private static void noProgressBoundaries() {
        var task = UUID.randomUUID();
        var guard = new DoomLoopGuard(new DoomLoopGuard.Policy(2, 5, 4, 6, "audit", Set.of()));
        for (int i = 0; i < 20; i++) {
            check(!guard.record(task, "query", "same", true), "unexpected stop on progressed=true");
        }
        check(guard.isOpen(task, "query", "same"), "unexpected closed signature");
        System.out.println("OBSERVED SUCCESS_AS_PROGRESS: 20 progressed=true records -> open"
                + " (guard contract; business novelty is caller responsibility)");
        for (int i = 0; i < 20; i++) {
            check(!guard.record(task, "query", "new-args-" + i, false), "unexpected stop");
        }
        System.out.println("OBSERVED ARGUMENT_CHURN: 20 distinct no-progress signatures -> open"
                + " (global budget/step limit remains separate)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

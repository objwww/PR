package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.util.Objects;

/**
 * Agent Replay Runner（AM4 M4-33）：候选侧 mock 工具面——实现 {@link ToolInvoker}
 * 让候选固定链以真实 Agent 代码组合 M4-32 回放网关（零链逻辑复制）。invoke 精确
 * 委托 ReplayToolGateway：REPLAY_HIT → EXECUTED 结果返回录制字节并计命中；
 * REPLAY_MISS → 模型可见族 REPLAY_MISS（Agent FAILED + 账本十码同因，可重试），
 * 绝不降级活执行。Token 由候选模型侧经 {@link #reportTokens} 申报累计（Native
 * 确定性链无 LLM，结构上零虚报）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public final class AgentReplayRunner implements ToolInvoker {

    private final ReplayToolGateway replay;
    private long totalInvocations;
    private long hits;
    private long misses;
    private long inputTokens;
    private long outputTokens;

    public AgentReplayRunner(ReplayToolGateway replayGateway) {
        this.replay = Objects.requireNonNull(replayGateway, "replayGateway");
    }

    /** 基线录制入口（与 invoke 同一 digest 面，M4-34 影子对照共用） */
    public String record(ToolGateway.ToolInvocation invocation, byte[] body) {
        return replay.record(invocation, body);
    }

    /** 精确回放并计数：命中返回录制字节，未命中模型可见族 REPLAY_MISS */
    @Override
    public ToolGateway.ToolInvocationResult invoke(ToolGateway.ToolInvocation invocation) {
        ReplayToolGateway.ReplayResult result = replay.invoke(invocation);
        totalInvocations++;
        if (result.kind() == ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_HIT) {
            hits++;
            return new ToolGateway.ToolInvocationResult(
                    ToolGateway.ToolInvocationResult.Kind.EXECUTED,
                    result.actionDigest(), result.body());
        }
        misses++;
        throw new ToolModelVisibleException(ToolModelVisibleReason.REPLAY_MISS,
                "回放账本无此精确动作记录（REPLAY_MISS，不降级活执行）");
    }

    /** 候选模型侧 Token 申报（多次调用累计） */
    public void reportTokens(long inputTokens, long outputTokens) {
        this.inputTokens += inputTokens;
        this.outputTokens += outputTokens;
    }

    /** 统计快照：覆盖率 = 命中/总调用（零调用记 0.0） */
    public ReplayStats stats() {
        double coverage = totalInvocations == 0 ? 0.0 : (double) hits / totalInvocations;
        return new ReplayStats(totalInvocations, hits, misses, coverage,
                inputTokens, outputTokens);
    }

    /** 一次回放运行的统计快照 */
    public record ReplayStats(long totalInvocations, long hits, long misses, double coverage,
            long inputTokens, long outputTokens) {
    }
}

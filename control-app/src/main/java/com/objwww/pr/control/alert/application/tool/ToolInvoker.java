package com.objwww.pr.control.alert.application.tool;

/**
 * 工具调用端口（AM4 M4-33 抽取）：Agent 基座对咽喉的最小依赖面——活执行
 * {@link ToolGateway} 与回放 {@code AgentReplayRunner}（M4-33）同形实现，
 * 候选链复用真实 Agent 代码零复制。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public interface ToolInvoker {

    ToolGateway.ToolInvocationResult invoke(ToolGateway.ToolInvocation invocation);
}

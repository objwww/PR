package com.objwww.pr.control.domain.tool;

import java.util.Objects;

/**
 * 工具策略引擎（M0 最小）：每次工具调用必过 check（§6.6 检查点）。
 * M0 规则只有两条：① 工具必须在白名单 Registry 中；② 必须是只读工具。
 * 参数级约束（路径必须在快照内等）随 M2 工具循环一起补，此处只留检查点骨架。
 */
public final class PolicyEngine {

    private final ToolRegistry registry;

    public PolicyEngine(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public PolicyVerdict check(ToolCall call) {
        Objects.requireNonNull(call, "call");
        return registry.find(call.toolName())
                .map(descriptor -> descriptor.readOnly()
                        ? PolicyVerdict.allow()
                        : PolicyVerdict.reject("非只读工具在 M0 不允许调用: " + call.toolName()))
                .orElseGet(() -> PolicyVerdict.reject("非白名单工具: " + call.toolName()));
    }
}

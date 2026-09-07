package com.objwww.pr.control.alert.domain.tool;

import java.util.Objects;
import java.util.Set;

/**
 * 工具策略（AM4 M4-16）：工具名允许集。<b>空策略硬失败</b>（评审收紧——无"或显式
 * 确认"模糊分支）；被拒工具从下发 LLM 的清单删除（清单裁剪由 Gateway/编排侧执行）；
 * Gateway 执行时仍二次鉴权（本 policy 即双闸的第二闸）。
 *
 * <p>风险等级只来自本地 ToolDefinition（伪造 readOnly annotation 不影响判定）——
 * 由结构保证：本策略不读任何外部 annotation，可执行集判定在 {@link ToolRisk#executable()}。
 */
public record ToolPolicy(Set<String> allowedTools) {

    public ToolPolicy {
        Objects.requireNonNull(allowedTools, "allowedTools");
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException("空工具策略硬失败（M4-16 评审收紧）："
                    + "至少须声明一个允许工具，否则应用拒绝启动");
        }
        allowedTools = Set.copyOf(allowedTools);
    }

    /** 双闸判定：该工具是否被策略允许（清单裁剪与执行时鉴权共用） */
    public boolean allows(String toolName) {
        return allowedTools.contains(toolName);
    }
}

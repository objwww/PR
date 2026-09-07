package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Agent 身份契约（AM4 M4-24）：<b>不可自由生 Agent</b>——每个 Agent 由固定的
 * prompt / tool allowlist / budget / 输出 schema 四件套定义，构造即校验、集合不可变，
 * 全部内容进 {@link #digest()}（字段序与集合序无关的稳定 sha256）。
 *
 * <p>digest 稳定性是注册表与配置面（config_digest）的对账锚点：任一固定件变更
 * digest 必变，完全相同的四件套 digest 必同。tool allowlist 为空合法
 * （Planner 等无工具 Agent）；budget 限额为空合法（Agent 级不设限，run 级预算
 * 仍由 RunBudget 执行）；budget 的 key 复用 {@link BudgetKind} 六维，不另造维度。
 * prompt 持有完整模板正文（非指针）——prompt 改一个字 digest 就变，可审计。
 */
public record AgentProfile(
        String name,
        String version,
        String prompt,
        String promptVersion,
        Set<String> toolAllowlist,
        Map<BudgetKind, Long> budgetLimits,
        Map<String, Object> outputSchema) {

    /** 与 ToolDefinition 同惯例：小写起点的稳定命名 */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][A-Za-z0-9._-]{0,31}$");

    public AgentProfile {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("AgentProfile.name 非法: " + name);
        }
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("AgentProfile.version 非法: " + version);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不得为空/blank");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不得为空/blank");
        }
        toolAllowlist = Set.copyOf(Objects.requireNonNull(toolAllowlist, "toolAllowlist"));
        Map<BudgetKind, Long> budgets = new TreeMap<>(Objects.requireNonNull(budgetLimits,
                "budgetLimits"));
        for (Map.Entry<BudgetKind, Long> e : budgets.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) {
                throw new IllegalArgumentException(
                        "budget 限额不得为负/空: " + e.getKey() + "=" + e.getValue());
            }
        }
        budgetLimits = java.util.Collections.unmodifiableMap(budgets);
        outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "outputSchema"));
    }

    /**
     * Agent digest：四件套整体 canonical 后 sha256（hex 64）。
     * 集合元素排序后进哈希（allowlist/预算的输入顺序无关）；outputSchema 由
     * InternalCanonicalJsonV1 递归排序（schema 字段序无关）。
     */
    public String digest() {
        // canonicalize 只接受 Map<String,?>/List/标量：Set → 排序 List，BudgetKind → name 串
        List<String> allowlist = toolAllowlist.stream().sorted().toList();
        Map<String, Long> budgets = new LinkedHashMap<>();
        for (Map.Entry<BudgetKind, Long> e : new TreeMap<>(budgetLimits).entrySet()) {
            budgets.put(e.getKey().name(), e.getValue());
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "agent-profile");
        content.put("name", name);
        content.put("version", version);
        content.put("prompt", prompt);
        content.put("promptVersion", promptVersion);
        content.put("toolAllowlist", allowlist);
        content.put("budgetLimits", budgets);
        content.put("outputSchema", outputSchema);
        return InternalCanonicalJsonV1.sha256(content);
    }
}

package com.objwww.pr.control.alert.domain.agent;

import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Agent 身份契约（AM4 M4-24 + R7 v2.1 §十一.2 完整性）：<b>不可自由生 Agent</b>——
 * 每个 Agent 由固定的 prompt / tool allowlist / budget / 输出 schema 四件套定义，
 * 构造即校验、集合不可变，全部内容进 {@link #digest()}（字段序与集合序无关的稳定
 * sha256）。
 *
 * <p>R7-X3（v2.1 §十一.2 角色字段清单）：
 * <ul>
 *   <li>outputSchema/inputSchema <b>递归深冻结</b>——构造期对外部传入结构做不可变
 *       深拷贝，调用方事后改嵌套层不再漂移本 Profile 的内容与 digest（RX05）；</li>
 *   <li>补齐 §十一.2 字段：inputSchema（派生 {@link #inputSchemaDigest()}）、
 *       {@link #phase()}、{@link #runtimeKind()}、{@link #requiredCapabilities()}、
 *       {@link #maxSteps()}、{@link #terminationPolicy()}；budget_caps 即既有
 *       budgetLimits（不重复建第二份限额面）；activation_selector 与工具版本约束
 *       归 X7 扩展角色卡（首期无候选选择面，不预建）；</li>
 *   <li>7 参旧构造形态保留（默认：INVESTIGATE / deterministic-single-tool / 单步 /
 *       single-pass 终止策略）——旧三角色装配与存量测试零改动（硬约束）。</li>
 * </ul>
 *
 * <p>digest 稳定性是注册表与配置面（config_digest）的对账锚点：任一固定件变更
 * digest 必变，完全相同的固定件集 digest 必同。tool allowlist 为空合法
 * （无工具 Agent）；budget 限额为空合法（Agent 级不设限，run 级预算仍由 RunBudget
 * 执行）；budget 的 key 复用 {@link BudgetKind} 六维，不另造维度。prompt 持有完整
 * 模板正文（非指针）——prompt 改一个字 digest 就变，可审计。
 */
public record AgentProfile(
        String name,
        String version,
        String prompt,
        String promptVersion,
        Set<String> toolAllowlist,
        Map<BudgetKind, Long> budgetLimits,
        Map<String, Object> outputSchema,
        Map<String, Object> inputSchema,
        AgentPhase phase,
        String runtimeKind,
        Set<String> requiredCapabilities,
        int maxSteps,
        String terminationPolicy) {

    /** 与 ToolDefinition 同惯例：小写起点的稳定命名 */
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9._-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][A-Za-z0-9._-]{0,31}$");

    /** AM4 旧形态（兼容构造，硬约束保留）：扩展字段全默认——调查阶段/确定性单工具运行器/单步 */
    public AgentProfile(String name, String version, String prompt, String promptVersion,
            Set<String> toolAllowlist, Map<BudgetKind, Long> budgetLimits,
            Map<String, Object> outputSchema) {
        this(name, version, prompt, promptVersion, toolAllowlist, budgetLimits, outputSchema,
                Map.of(), AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 1, "single-pass");
    }

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
        if (phase == AgentPhase.DIAGNOSE) {
            // v2.1 §五.2/§十一.2：独立诊断阶段已取消（职责合入主 Agent），新 Profile 禁用
            throw new IllegalArgumentException(
                    "AgentPhase.DIAGNOSE 已随 v2.1 退役（诊断职责合入 PRIMARY），不得新建");
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
        budgetLimits = Collections.unmodifiableMap(budgets);
        // R7-X3：递归深冻结（修 Map.copyOf 顶层复制缺口——嵌套层与调用方共享即 digest 可漂移）
        outputSchema = deepFreezeMap(Objects.requireNonNull(outputSchema, "outputSchema"));
        inputSchema = deepFreezeMap(Objects.requireNonNull(inputSchema, "inputSchema"));
        phase = Objects.requireNonNull(phase, "phase");
        if (runtimeKind == null || runtimeKind.isBlank()) {
            throw new IllegalArgumentException("runtimeKind 不得为空/blank");
        }
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(requiredCapabilities,
                "requiredCapabilities"));
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps 必须 >= 1: " + maxSteps);
        }
        if (terminationPolicy == null || terminationPolicy.isBlank()) {
            throw new IllegalArgumentException("terminationPolicy 不得为空/blank");
        }
    }

    /**
     * Agent digest：固定件集整体 canonical 后 sha256（hex 64）。
     * 集合元素排序后进哈希（allowlist/预算/能力的输入顺序无关）；schema 由
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
        content.put("inputSchema", inputSchema);
        content.put("phase", phase.name());
        content.put("runtimeKind", runtimeKind);
        content.put("requiredCapabilities", requiredCapabilities.stream().sorted().toList());
        content.put("maxSteps", maxSteps);
        content.put("terminationPolicy", terminationPolicy);
        return InternalCanonicalJsonV1.sha256(content);
    }

    /** §十一.2 manifest 面：prompt 正文+版本的独立摘要（hex 64） */
    public String promptDigest() {
        return InternalCanonicalJsonV1.sha256(Map.of(
                "prompt", prompt, "promptVersion", promptVersion));
    }

    /** §十一.2 manifest 面：输入契约摘要（hex 64；空输入契约合法） */
    public String inputSchemaDigest() {
        return InternalCanonicalJsonV1.sha256(inputSchema);
    }

    /** §十一.2 manifest 面：输出契约摘要（hex 64） */
    public String outputSchemaDigest() {
        return InternalCanonicalJsonV1.sha256(outputSchema);
    }

    /**
     * 递归深冻结：Map/List 逐层拷贝为不可变结构；叶子只接受 String/Number/Boolean/null
     * （与 InternalCanonicalJsonV1 可哈希类型域一致，构造期拒绝不可 canonicalize 的值）。
     */
    private static Map<String, Object> deepFreezeMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException("schema 的 key 不得为 null");
            }
            copy.put(e.getKey(), deepFreezeValue(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object deepFreezeValue(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case Number n -> {
                return n;
            }
            case Boolean b -> {
                return b;
            }
            case Map<?, ?> m -> {
                Map<String, Object> asStringKeys = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!(e.getKey() instanceof String key)) {
                        throw new IllegalArgumentException(
                                "schema 嵌套 Map 的 key 必须是 String，实际: " + e.getKey());
                    }
                    asStringKeys.put(key, e.getValue());
                }
                return deepFreezeMap(asStringKeys);
            }
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) {
                    copy.add(deepFreezeValue(item));
                }
                return Collections.unmodifiableList(copy);
            }
            default -> throw new IllegalArgumentException(
                    "schema 只接受 Map/List/String/Number/Boolean/null，实际: "
                            + value.getClass().getName());
        }
    }
}

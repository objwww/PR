package com.objwww.pr.control.domain.review;

import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.ai.ModelClient;
import com.objwww.pr.control.domain.ai.ModelRequest;
import com.objwww.pr.control.domain.ai.ModelResult;
import com.objwww.pr.control.domain.snapshot.SnapshotTree;
import com.objwww.pr.control.domain.tool.PolicyEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评审 Agent 主循环（domain 服务，§3/§6.6）：外层确定性状态机 + 内层单轮模型调用。
 *
 * <p>确定性流程（同输入同产出）：
 * <ol>
 *   <li>文件选择：快照条目已按路径字典序定序，按 maxFiles/maxBytes 预算顺序截断，
 *       截断记数（不允许"悄悄不看"）；</li>
 *   <li>打包：diff 全文 + 选中文件内容打成单个 prompt bundle（M0 单轮，多 bundle 分组是 M2+）；</li>
 *   <li>模型调用：ModelBudgetGuard 调用前后两道校验，超时/超预算抛领域异常（Step FAILED，不降级）；</li>
 *   <li>解析：结构化 findings JSON，整体乱输出 → {@link ModelOutputParseException} 安全失败；</li>
 *   <li>映射：FindingMapper 按 existing_code 重定位行号 + fingerprint，丢弃计数。</li>
 * </ol>
 *
 * <p>PolicyEngine 是 M2 工具循环的检查点留缝：M0 单轮不开放工具调用，
 * 但循环结构预留"每次工具调用前必过 PolicyEngine.check"的挂载点（§6.6）。
 */
public final class ReviewAgentLoop {

    private final ModelClient modelClient;
    private final ModelBudgetGuard budgetGuard;
    private final FindingMapper findingMapper;
    @SuppressWarnings("unused") // M2 工具循环检查点留缝：多轮工具调用放开时在此过 Policy
    private final PolicyEngine policyEngine;

    public ReviewAgentLoop(ModelClient modelClient, ModelBudgetGuard budgetGuard,
                           FindingMapper findingMapper, PolicyEngine policyEngine) {
        this.modelClient = Objects.requireNonNull(modelClient);
        this.budgetGuard = Objects.requireNonNull(budgetGuard);
        this.findingMapper = Objects.requireNonNull(findingMapper);
        this.policyEngine = Objects.requireNonNull(policyEngine);
    }

    /**
     * 执行一轮评审。
     *
     * @param snapshot head 快照（安全解包后的内存树，条目已按路径字典序定序）
     * @param headSha  评审对象代码身份（fingerprint 组分）
     * @param diffText base..head unified diff 全文（prompt 组分）
     * @param budget   确定性预算
     */
    public ReviewOutcome review(SnapshotTree snapshot, String headSha, String diffText,
                                ReviewBudget budget) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(diffText, "diffText");
        Objects.requireNonNull(budget, "budget");

        // 1) 确定性文件选择：字典序已定序，预算内顺序收录，超预算即截断并记数
        List<SnapshotTree.Entry> candidates = snapshot.entries();
        List<SnapshotTree.Entry> selected = new ArrayList<>();
        long bytes = 0;
        for (SnapshotTree.Entry entry : candidates) {
            if (selected.size() >= budget.maxFiles() || bytes + entry.content().length > budget.maxBytes()) {
                break; // 截断点确定：排序固定 + 顺序扫描，同输入同截断位置
            }
            selected.add(entry);
            bytes += entry.content().length;
        }
        int truncated = candidates.size() - selected.size();

        // 2) 打包：diff + 选中文件内容（单 bundle，M0 单轮）
        String prompt = buildPrompt(diffText, selected);

        // 3) 模型调用：预算守卫前后两道（守卫也在适配器内执行，此处是领域侧独立防线）
        ModelRequest request = new ModelRequest(prompt, budget.maxCompletionTokens(), budget.timeout());
        budgetGuard.validate(request);
        ModelResult result = modelClient.complete(request);
        budgetGuard.checkUsage(request, result.tokenUsage());

        // 4) 解析：整体乱输出 → 安全失败异常（不产出半个 ReviewOutcome）
        FindingJsonParser.ParseResult parsed = new FindingJsonParser().parse(result.content());

        // 5) 行号工程映射：不信模型行号，按 existing_code 重定位
        Map<String, String> contents = new LinkedHashMap<>();
        for (SnapshotTree.Entry entry : selected) {
            contents.put(entry.path(), new String(entry.content(), StandardCharsets.UTF_8));
        }
        FindingMapper.MappingResult mapped = findingMapper.map(headSha, contents, parsed.findings());

        return new ReviewOutcome(mapped.findings(), mapped.droppedCount(), parsed.malformedCount(),
                candidates.size(), selected.size(), truncated, result.tokenUsage());
    }

    /** prompt 组装（确定性：文件按定序清单逐节拼接） */
    private static String buildPrompt(String diffText, List<SnapshotTree.Entry> selected) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("""
                你是代码评审器。基于以下 diff 与相关文件全文，输出评审发现。
                只输出一个 JSON 数组，不要输出任何其他文字。每条元素字段：
                {"file": 文件路径, "line": 你估计的行号, "existing_code": 问题代码的原文片段(逐字引用),
                 "rule": 规则标识, "severity": BLOCKER|MAJOR|MINOR|INFO, "message": 问题描述}
                没有发现就输出 []。

                ==== DIFF ====
                """);
        sb.append(diffText).append("\n\n==== FILES ====\n");
        for (SnapshotTree.Entry entry : selected) {
            sb.append("---- FILE: ").append(entry.path()).append(" ----\n");
            sb.append(new String(entry.content(), StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }
}

package com.objwww.pr.control.alert.application;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 根因结论六要素写作规约（BA-177 共享化：prompt 文本与评分检出同源，防口径漂移）。
 *
 * <p>纪律立法见 .agent-notes/根因结论六要素纪律.md（六要素定义 + 把握三档语义 +
 * 风格要求 + 合格/违例示例）。本类把该纪律落成两个消费面：
 * <ul>
 *   <li>{@link #PROMPT_SECTION}——主 Agent prompt 的【报告写作要求】段权威文本
 *       （am4-native-v9 = v8 段 + 把握硬措辞 + 坏示例 + 禁黑话三条，即
 *       deploy/alert/prompts/draft-primary-v9-写作要求增量.md 三增量的投产形态）；</li>
 *   <li>{@link #confidenceLevelOf(String)}——"有多大把握"要素的共享检出，供
 *       SixElementsChecker（评分面）调用。检出认两种锚：agent 原文的把握硬措辞
 *       「把握：HIGH|MEDIUM|LOW」，与 BA-175 确定性摘要的「结论置信度：高/中/未定论」
 *       （高→HIGH / 中→MEDIUM / 未定论→LOW）。检不出=empty，不猜档位。
 * </ul>
 *
 * <p>改规约=改评分口径：本节文本与检出锚必须同步演进，单边改动即口径漂移。
 */
public final class ReportWritingRubric {

    /** 把握锚一：agent 原文硬措辞（v9 增量一锚定的机器检出点） */
    private static final Pattern CONFIDENCE_PHRASE =
            Pattern.compile("把握[：:]\\s*(HIGH|MEDIUM|LOW)");

    /** 把握锚二：BA-175 确定性摘要措辞（NativeReportAdapter 结论置信度行） */
    private static final Pattern CONFIDENCE_ZH =
            Pattern.compile("结论置信度[：:]\\s*(高|中|未定论)");

    /**
     * prompt【报告写作要求】段权威文本（v9）。相对 v8 段的三处增量：
     * 把握短语硬规则（评分检出锚）、不合格反例、禁黑话/禁无量化/禁伪证据三条。
     * 与 {@link #confidenceLevelOf} 的锚一逐字一致——改这里必须同步改检出。
     */
    public static final String PROMPT_SECTION = """
            # 报告写作要求（六要素，给人看的话）
            final 里各 claim 的 statement 合起来，要让读者一段话看懂六件事：发生了什么（症状）→根因是什么→凭什么判断（哪些证据面互证）→影响多大（能量化就写量化事实，如"重复订单计数升至 2"）→有多大把握→建议怎么办。硬规则：
            - 证据 id（UUID）只能出现在 evidence_refs 与 evidence_roles 字段里——statement 正文写自然中文，禁止把证据 id、英文故障码、内部枚举写进 statement（正文内容会原样透传到报告摘要给人读）；
            - statement 一句话说清一个结论，量化事实优先写进 ROOT_CAUSE 的 statement（报告的影响面从该句提取）；
            - ROOT_CAUSE 的 statement 结尾必须带把握短语，格式固定：「把握：HIGH（多源一致且机理通顺）」/「把握：MEDIUM（多源一致）」/「把握：LOW（单源证据）」三选一，括号内写依据；不确定就是 LOW，不丢这个短语（丢了=报告不完整，评分按缺要素算）；
            - 禁黑话：赋能、抓手、闭环、沉淀、拉齐——出现即返工；
            - 禁无量化描述："成功率显著下降"不合格，"成功率 99.2%→87.4%"合格；
            - 禁伪证据引用：statement 正文不得出现"日志显示""指标表明"却不给出对应 evidence_refs 的写法——说不出是哪条证据，就写"证据不足"。
            措辞范例（告警域）："订单服务在冻结窗内重复创单，同一意图被重复执行，重复订单计数升至 2；指标曲线与日志重复 insert 记录两处互证一致"——而不是"order-arena 发生 IDEMPOTENCY_BYPASS {uuid:SUPPORTS}"。
            反例（不合格，出现即返工）："经分析，该问题系多因素耦合导致，建议持续关注并加强治理。"哪里坏：没有根因（多因素=没说）、没有证据（凭什么是）、没有量化（影响多大？）、没有把握、建议是空话。六件里五件没有，这一段等于没写。
            """;

    private ReportWritingRubric() {
    }

    /**
     * "有多大把握"共享检出：先认 agent 原文把握硬措辞（HIGH/MEDIUM/LOW 原样返回），
     * 再认确定性摘要「结论置信度：高/中/未定论」（映射 HIGH/MEDIUM/LOW）。两锚皆无=empty。
     */
    public static Optional<String> confidenceLevelOf(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CONFIDENCE_PHRASE.matcher(text);
        if (m.find()) {
            return Optional.of(m.group(1));
        }
        Matcher zh = CONFIDENCE_ZH.matcher(text);
        if (zh.find()) {
            return Optional.of(switch (zh.group(1)) {
                case "高" -> "HIGH";
                case "中" -> "MEDIUM";
                default -> "LOW";
            });
        }
        return Optional.empty();
    }
}

package com.objwww.pr.control.eval.domain.litellm;

import java.math.BigDecimal;
import java.util.List;

/**
 * LiteLLM proxy 管理面端口（M3-25；master key 仅 env 注入，INV-AM3-3 同纪律）。
 *
 * <p>契约按 spike Exp5 实测的 litellm 1.89.0：
 * {@code POST /key/generate}（per-EvalRun 虚拟 key，{@code key_alias} 即对账锚、
 * {@code max_budget} 即硬拦预算——超限在 auth 层 429 拒绝、上游零流量）、
 * {@code GET /spend/logs}（SpendLogs 投影；<b>刻意不提供时间窗过滤参数</b>——
 * P0-8 禁时间窗归因，行筛拣只按 key 别名/metadata 在域侧完成）。
 * spend 日志 flush 异步（spike 实测 ~16s），调用方需容忍延迟后重查。
 */
public interface LiteLlmAdminPort {

    /** 铸 per-EvalRun 虚拟 key；返回 key 明文（调用方负责交付部署侧，不入日志） */
    String mintRunKey(String keyAlias, BigDecimal maxBudgetUsd);

    /** SpendLogs 行全集（无时间参数；行筛拣由域侧对账器完成） */
    List<SpendRecord> spendLogs();
}

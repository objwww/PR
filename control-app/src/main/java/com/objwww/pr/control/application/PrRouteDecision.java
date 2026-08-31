package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;

import java.time.Duration;
import java.util.UUID;

/**
 * 权威读路由决策（M1-T05，方案 §4.3/§4.4 判定树的输出，UT-16）。
 * 决策与执行分离：PrEventAuthoritativeReader 只产决策（不写库、不调用 T1，可单测），
 * InboxProcessor 按决策分支驱动执行（completeIgnored / completeProcessed / RETRY_WAIT /
 * T0+T1 / draft 预检 / T-close / T-draft）。
 */
public sealed interface PrRouteDecision {

    /** LWW 快筛拦截：明显陈旧的乱序事件，零 API（ST-11）→ inbox IGNORED */
    record IgnoredStale() implements PrRouteDecision {
    }

    /**
     * 幂等收敛点（ST-21/E2E-20）：远端 (head, base) 与投影 current revision 的二元组一致，
     * 且存在同策略代的 active Run——一切已就绪，零动作 → inbox PROCESSED
     */
    record IdempotentDone(UUID activeRunId) implements PrRouteDecision {
    }

    /** 远端 open + 非 draft：以远端值为准走全量 T0/T1（E2E-10：head 或 base 任一变化都新 Revision） */
    record FullReview(FetchResult.Found remote) implements PrRouteDecision {
    }

    /**
     * T-reopen（I15/ST-20，INC-26）：reopened 是状态语义换届，不是 diff 语义——远端
     * open 非 draft 且事件为 reopened 时，无论 (head,base) 是否变化都强制
     * publication_epoch+1 + 新 Run（旧世代同 epoch 的待发命令必须被 fence，
     * 不能因代码没改就复用旧世代）。
     */
    record Reopen(FetchResult.Found remote) implements PrRouteDecision {
    }

    /**
     * 远端 open + draft 且无需换届（无在途 Run、非 converted_to_draft 迁移事件）：
     * 廉价预检——只刷投影 + 水印，零 T0/Run/Outbox/模型（I11，ST-12）
     */
    record DraftPrecheck(FetchResult.Found remote) implements PrRouteDecision {
    }

    /**
     * T-draft（方案 §4.4 决策表）：确认 draft=true 且需要换届（converted_to_draft 事件，
     * 或在途 Run 仍在而远端已转 draft）→ 同事务 投影 draft=true + epoch+1 + Run SUPERSEDED
     */
    record ConvertToDraft(FetchResult.Found remote) implements PrRouteDecision {
    }

    /**
     * T-close（方案 §4.4）：远端 closed/merged，或 404 经 sanity 读确认 repo 可读
     * （PR 真没了按关处理）→ 同事务 投影 CLOSED + epoch+1 + 在途 Run SUPERSEDED。
     * remote 为 null 表示 404 路径（远端事实不可得，投影用事件载荷兜底值，EX-18 精神）。
     */
    record Close(FetchResult.Found remote) implements PrRouteDecision {
    }

    /** 权限异常/限流/5xx/404 但 sanity 失败：RETRY_WAIT，retryAfter 尊重 Retry-After（EX-16） */
    record Retry(String reason, Duration retryAfter) implements PrRouteDecision {
    }
}

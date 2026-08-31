package com.objwww.pr.control.domain.port;

import java.time.Duration;
import java.time.Instant;

/**
 * GitHub PR 元数据只读端口（M1-T05，方案 §3.1）：权威读的唯一通道。
 * Run 创建/废弃前一律经本端口读 GitHub 当前状态，以远端为准（修正 #6）。
 *
 * <p>结果类型必须可区分（不得吞成 Optional——404/403/429/5xx 是五种不同的运维事实，
 * 路由决策各异：404 走 sanity 读、403 权限异常不冒充事实、429 尊重 retry-after、5xx 退避）：
 * <ul>
 *   <li>{@link FetchResult.Found}：200，含 (state, draft, merged, headSha, baseRef, baseSha,
 *       updatedAt)——E2E-10 要求漂移比对用 (head, base) 二元组，故 base 一并返回；</li>
 *   <li>{@link FetchResult.NotFound}：404/410（注意 F-3：GitHub 用 404 替代 403 隐藏私有
 *       资源，404 本身不可区分"不存在"与"无权限"，须再经 {@link #checkRepoReadable}
 *       sanity 读判别）；</li>
 *   <li>{@link FetchResult.Forbidden}：403（含 GitHub 以 403 表达的 rate-limit）；</li>
 *   <li>{@link FetchResult.RateLimited}：429 + Retry-After（EX-16）；</li>
 *   <li>{@link FetchResult.Unavailable}：5xx/超时/网络错误。</li>
 * </ul>
 * 只读；不缓存（对账要当下事实）。token 由实现侧经 CredentialTokenPort 申请，
 * 接口签名不带 token（凭证不出现在 domain 语义里）。
 */
public interface GitHubPrMetadataPort {

    /** 读 PR 当前元数据：GET /repos/{repoFullName}/pulls/{prNumber} */
    FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber);

    /**
     * sanity 读（方案 §4.3 404 分支 / EX-17、E2E-18 精神）：GET /repos/{repoFullName}。
     * 用于判别 404 的两种语义——repo 可读说明 token/权限/仓库皆正常，PR 是真没了（按关闭处理）；
     * repo 也不可读说明是权限/可用性异常，绝不冒充"PR 不存在"这个事实。
     */
    SanityResult checkRepoReadable(long installationId, String repoFullName);

    /** 权威读结果（sealed：编译期强制穷举，新增种类时所有 switch 编译报错） */
    sealed interface FetchResult {

        /** 200：以远端返回为准的事实集；updatedAt 可能为 null（远端缺/非法字段时不猜，EX-18） */
        record Found(String state, boolean draft, boolean merged,
                     String headSha, String baseRef, String baseSha,
                     Instant updatedAt) implements FetchResult {

            /** 远端是否 open（GitHub state 只有 open/closed 两值；merged 由 merged 标志区分） */
            public boolean isOpen() {
                return "open".equalsIgnoreCase(state);
            }
        }

        /** 404/410：PR 或仓库不可达（两种语义见类注释，须 sanity 读判别） */
        record NotFound() implements FetchResult {
        }

        /** 403：权限异常或 GitHub 以 403 表达的限流——RETRY，不动作（EX-16/EX-17 精神） */
        record Forbidden() implements FetchResult {
        }

        /** 429：限流，retryAfter 来自 Retry-After 头（可能为 null）；EX-16 尊重之 */
        record RateLimited(Duration retryAfter) implements FetchResult {
        }

        /** 5xx/超时/网络错误：RETRY 退避 */
        record Unavailable(String reason) implements FetchResult {
        }
    }

    /** sanity 读结果：repo 可读 = token/权限/仓库皆正常 */
    enum SanityResult {
        READABLE,
        UNREADABLE
    }
}

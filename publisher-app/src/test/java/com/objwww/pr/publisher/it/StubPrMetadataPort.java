package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;

import java.time.Instant;

/**
 * publisher IT 侧权威读 stub（M1-T05）：逐场可编程应答 + 调用计数。
 * 默认 next=null 即"永不应答"——只该走死信/IGNORED 的路径用它兜底
 * （一旦代码路径意外触网，立即 NPE 暴露，比静默默认值更诚实）。
 */
final class StubPrMetadataPort implements GitHubPrMetadataPort {

    FetchResult next;
    SanityResult sanity = SanityResult.READABLE;
    int fetchCalls;
    int sanityCalls;

    /** 常规应答：open/closed + draft/merged + (head, base) + updatedAt */
    StubPrMetadataPort remote(String state, boolean draft, boolean merged,
                              String headSha, String baseSha, Instant updatedAt) {
        next = new FetchResult.Found(state, draft, merged, headSha, ItHarness.BASE_REF, baseSha,
                updatedAt);
        return this;
    }

    @Override
    public FetchResult fetchPullRequest(long installationId, String repoFullName, int prNumber) {
        fetchCalls++;
        return next;
    }

    @Override
    public SanityResult checkRepoReadable(long installationId, String repoFullName) {
        sanityCalls++;
        return sanity;
    }
}

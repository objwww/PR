package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PRRevision;
import com.objwww.pr.control.domain.model.PRSubject;
import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.FetchResult;
import com.objwww.pr.control.domain.port.GitHubPrMetadataPort.SanityResult;
import com.objwww.pr.control.domain.repository.PRRevisionRepository;
import com.objwww.pr.control.domain.repository.PRSubjectRepository;
import com.objwww.pr.control.domain.repository.ReviewRunRepository;
import com.objwww.pr.control.domain.service.StaleEventGuard;
import com.objwww.pr.control.interfaces.webhook.PullRequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 权威读编排（M1-T05，方案 §4.3 判定树）：webhook 只是"状态可能变了"的通知，
 * 权威永远是 GitHub 当前状态。本类把"事件 + 投影 + 权威读结果"折叠成一个路由决策
 * （{@link PrRouteDecision}），**不写库、不调用 T1**——决策与执行分离（UT-16 可单测）。
 *
 * <p>判定树（§4.3 原文 + 两处评审修正落点）：
 * <ol>
 *   <li>LWW 快筛（StaleEventGuard 纯函数）：STALE → IgnoredStale，零 API（ST-11）；
 *       等于水印放行（同秒不误杀，UT-12）；缺 updated_at → UNKNOWN 直接进权威读（EX-18）；</li>
 *   <li>权威读 OK → 先幂等收敛（远端 (head,base) == 投影 current revision 二元组
 *       且存在同策略代 active Run → IdempotentDone，ST-21 收敛点；E2E-10：比的是二元组
 *       不是只比 head），再按远端状态分支：open+非 draft → FullReview（以远端值为准）；
 *       open+draft → DraftPrecheck / ConvertToDraft（见下）；closed/merged → Close；</li>
 *   <li>draft 细分（§4.4 决策表 + I15 防御补齐）：converted_to_draft 事件确认 draft=true，
 *       或远端已 draft 但仍有在途 active Run（如 converted_to_draft 的 webhook 丢了、
 *       后续 synchronize 才发现）→ ConvertToDraft（T-draft：epoch+1 + SUPERSEDED）；
 *       否则 → DraftPrecheck（廉价预检，零 Run 零 Outbox）；</li>
 *   <li>404 → <b>sanity 读</b>（方案 §4.3 原文此处少一层，按 EX-17/E2E-18 原则补齐：
 *       F-3——GitHub 用 404 替代 403 隐藏私有资源，404 本身不可区分"不存在"与"无权限"）：
 *       repo 可读 → Close（PR 真没了按关处理）；repo 不可读 → Retry（权限异常不冒充事实）；</li>
 *   <li>403 / 429 / 5xx → Retry（429 带 retryAfter，EX-16）。</li>
 * </ol>
 *
 * <p>零 Spring 注解，唯一装配点在 infrastructure/config/ReviewFlowConfig（docker profile）。
 */
public class PrEventAuthoritativeReader {

    private static final Logger log = LoggerFactory.getLogger(PrEventAuthoritativeReader.class);

    private final PRSubjectRepository subjectRepository;
    private final PRRevisionRepository revisionRepository;
    private final ReviewRunRepository runRepository;
    private final GitHubPrMetadataPort metadataPort;
    /** 当前部署的策略代（E2E-09：policy 是部署配置，"同策略代"必须与应用当前值比对） */
    private final String policyVersion;

    public PrEventAuthoritativeReader(PRSubjectRepository subjectRepository,
                                      PRRevisionRepository revisionRepository,
                                      ReviewRunRepository runRepository,
                                      GitHubPrMetadataPort metadataPort,
                                      String policyVersion) {
        this.subjectRepository = Objects.requireNonNull(subjectRepository);
        this.revisionRepository = Objects.requireNonNull(revisionRepository);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.metadataPort = Objects.requireNonNull(metadataPort);
        this.policyVersion = Objects.requireNonNull(policyVersion);
    }

    /** §4.3 判定树：事件 → 路由决策（纯读取 + 纯函数判定，零写库） */
    public PrRouteDecision decide(PullRequestEvent event) {
        // 1) LWW 快筛（唯一零 API 的分支）
        Optional<PRSubject> subjectOpt = subjectRepository.findByRepositoryAndPrNumber(
                event.repositoryId(), event.prNumber());
        StaleEventGuard.Verdict verdict = StaleEventGuard.screen(
                event.updatedAt(), subjectOpt.map(PRSubject::getLastEventUpdatedAt).orElse(null));
        if (verdict == StaleEventGuard.Verdict.STALE) {
            return new PrRouteDecision.IgnoredStale();
        }

        // 2) 权威读（PASS 与 UNKNOWN 都到这里——缺 updated_at 不猜，EX-18）
        FetchResult fetch = metadataPort.fetchPullRequest(
                event.installationId(), event.repositoryFullName(), event.prNumber());
        return switch (fetch) {
            case FetchResult.Found found -> decideOnFound(event, found, subjectOpt.orElse(null));
            case FetchResult.NotFound notFound -> decideOnNotFound(event);
            case FetchResult.Forbidden forbidden ->
                    new PrRouteDecision.Retry("forbidden", null);
            case FetchResult.RateLimited rateLimited ->
                    new PrRouteDecision.Retry("rate_limited", rateLimited.retryAfter());
            case FetchResult.Unavailable unavailable ->
                    new PrRouteDecision.Retry("unavailable:" + unavailable.reason(), null);
        };
    }

    private PrRouteDecision decideOnFound(PullRequestEvent event, FetchResult.Found remote,
                                          PRSubject subject) {
        if (!remote.isOpen()) {
            // 远端 closed/merged → T-close（以远端为准，哪怕事件是 synchronize，图 3-2 原则）
            return new PrRouteDecision.Close(remote);
        }
        if (remote.draft()) {
            // §4.4 决策表 + I15 防御补齐：迁移事件（converted_to_draft）或仍有在途 Run
            // （其旧 epoch 的 PENDING 命令必须被 fence）→ T-draft；否则廉价预检
            boolean migrationEvent = "converted_to_draft".equals(event.action());
            if (migrationEvent || hasActiveRunOfCurrentGeneration(subject)) {
                return new PrRouteDecision.ConvertToDraft(remote);
            }
            return new PrRouteDecision.DraftPrecheck(remote);
        }
        // ST-20/I15：reopened 是状态换届不是 diff 语义——即使 (head,base) 未变也强制
        // 新 epoch 新 Run。放在 draft 分支之后（reopened 时仍是 draft → 廉价预检不换届，
        // §4.4 决策表第一行）、收敛点之前（收敛点会把它误判成幂等零动作）。
        if ("reopened".equals(event.action())) {
            return new PrRouteDecision.Reopen(remote);
        }
        // open + 非 draft：先幂等收敛点（ST-21），否则全量
        if (isAlreadyConverged(subject, remote)) {
            return new PrRouteDecision.IdempotentDone(
                    activeRunOfCurrentGeneration(subject).orElseThrow().getId());
        }
        return new PrRouteDecision.FullReview(remote);
    }

    /**
     * ST-21 收敛点判定：投影已是 open+非 draft、current revision 的 (head, base) 二元组
     * 与远端一致（E2E-10：不是只比 head）、且存在同策略代（应用当前 policyVersion，E2E-09）
     * 的 active Run。
     */
    private boolean isAlreadyConverged(PRSubject subject, FetchResult.Found remote) {
        if (subject == null || subject.getState() != PrSubjectState.OPEN || subject.isDraft()
                || subject.getCurrentRevisionId() == null) {
            return false;
        }
        Optional<PRRevision> revision = revisionRepository.findById(subject.getCurrentRevisionId());
        if (revision.isEmpty()
                || !revision.get().getHeadSha().equals(remote.headSha())
                || !revision.get().getBaseSha().equals(remote.baseSha())) {
            return false;
        }
        return activeRunOfCurrentGeneration(subject).isPresent();
    }

    private boolean hasActiveRunOfCurrentGeneration(PRSubject subject) {
        return activeRunOfCurrentGeneration(subject).isPresent();
    }

    /** 同策略代的在途 Run：策略代以应用当前 policyVersion 为准（policy 是部署配置，E2E-09） */
    private Optional<ReviewRun> activeRunOfCurrentGeneration(PRSubject subject) {
        if (subject == null) {
            return Optional.empty();
        }
        List<ReviewRun> active = runRepository.findActiveByPrSubjectId(subject.getId());
        return active.stream()
                .filter(r -> policyVersion.equals(r.getPolicyVersion()))
                .findFirst();
    }

    /**
     * 404 分支（EX-17/E2E-18 精神，方案 §4.3 原文缺 sanity 层、按评审修正补齐）：
     * sanity 读通过（repo 可读 = token/权限/仓库皆正常）→ PR 真没了，按关处理；
     * sanity 失败 → 权限/可用性异常，RETRY 不动作——权限问题绝不冒充"PR 不存在"的事实。
     */
    private PrRouteDecision decideOnNotFound(PullRequestEvent event) {
        SanityResult sanity = metadataPort.checkRepoReadable(
                event.installationId(), event.repositoryFullName());
        if (sanity == SanityResult.READABLE) {
            log.info("权威读 404 且 sanity 读通过，按 PR 关闭处理 repo={} pr={}",
                    event.repositoryFullName(), event.prNumber());
            return new PrRouteDecision.Close(null);
        }
        return new PrRouteDecision.Retry("not_found_sanity_failed", null);
    }
}

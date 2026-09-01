package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PUBLISH_REVIEW：body 内置隐藏 marker {@code <!-- ai-review:{operation_id} -->}（reconcile 探针），
 * commit_id 绑 head_sha——head 变了 GitHub 自己 422（B-1 缓解，EX-02 归类 SUPERSEDED）。
 * 探测窗口：分页查该 PR 的 reviews，匹配 body 内 marker。
 */
public final class PublishReviewHandler implements PublicationHandler {

    public static final String MARKER_PREFIX = "<!-- ai-review:";
    public static final String MARKER_SUFFIX = " -->";

    static final int PROBE_PER_PAGE = 100;

    /** marker 由 operation_id 唯一推导（与 control 铸造 payload.marker 同源） */
    public static String markerOf(OperationId operationId) {
        return MARKER_PREFIX + operationId + MARKER_SUFFIX;
    }

    @Override
    public CommandType commandType() {
        return CommandType.PUBLISH_REVIEW;
    }

    @Override
    public PublicationResourceType resourceType() {
        return PublicationResourceType.REVIEW;
    }

    @Override
    public String resourceMarker(ClaimedCommand command) {
        return markerOf(command.operationId());
    }

    @Override
    public TypedWriteRequest buildRequest(ClaimedCommand command, Map<String, Object> payload) {
        String commitId = CreateCheckHandler.requiredText(payload, "commit_id");
        Object prNumber = payload.get("pr_number");
        if (prNumber == null) {
            throw new IllegalArgumentException("payload 缺必需字段: pr_number");
        }
        return new TypedWriteRequest(GitHubOperation.CREATE_REVIEW,
                CreateCheckHandler.requiredText(payload, "repo"),
                Map.of("commit_id", commitId,
                        "pr_number", prNumber,
                        "event", "COMMENT", // M0：只留观察意见，不阻塞合并
                        "body", buildBody(command, payload)));
    }

    /** body = 人读摘要 + 行尾隐藏 marker（marker 是 reconcile 唯一可靠探针，不可省） */
    String buildBody(ClaimedCommand command, Map<String, Object> payload) {
        StringBuilder body = new StringBuilder("AI Code Review\n");
        Object findings = payload.get("findings");
        if (findings instanceof List<?> list) {
            body.append("\n共 ").append(list.size()).append(" 个发现：\n");
            for (Object item : list) {
                if (item instanceof Map<?, ?> f) {
                    body.append("- `").append(f.get("file")).append('`');
                    if (f.get("line_start") != null) {
                        body.append(" 行 ").append(f.get("line_start"));
                    }
                    body.append(" [").append(f.get("severity")).append("] ")
                            .append(f.get("message")).append('\n');
                }
            }
        }
        body.append('\n').append(markerOf(command.operationId()));
        return body.toString();
    }

    @Override
    public TypedOutcome interpret(TypedResponse response) {
        if ((response.status() == 200 || response.status() == 201) && response.objectBody() != null) {
            return TypedOutcome.confirmed(
                    String.valueOf(response.objectBody().get("id")),
                    (String) response.objectBody().get("html_url"));
        }
        if (response.isServerError()) {
            return TypedOutcome.serverRetryable("status=" + response.status());
        }
        if (response.status() == 401 || response.status() == 403) {
            return TypedOutcome.authFailed("status=" + response.status());
        }
        if (response.status() == 422) {
            // Reviews API 对 commit_id 非 head 的 422 是确定性否定（§6.3）→ SUPERSEDED
            return StaleHeadClassifier.isHeadMismatch422(response)
                    ? TypedOutcome.staleHead(CreateCheckHandler.detailOf(response))
                    : TypedOutcome.failedTerminal("GITHUB_422", CreateCheckHandler.detailOf(response));
        }
        return TypedOutcome.failedTerminal("GITHUB_" + response.status(),
                CreateCheckHandler.detailOf(response));
    }

    @Override
    public TypedReadRequest buildProbe(ClaimedCommand command, Map<String, Object> payload) {
        Object prNumber = payload.get("pr_number");
        if (prNumber == null) {
            throw new IllegalArgumentException("payload 缺必需字段: pr_number");
        }
        return new TypedReadRequest(GitHubOperation.LIST_REVIEWS,
                CreateCheckHandler.requiredText(payload, "repo"),
                Map.of("pr_number", prNumber,
                        "per_page", PROBE_PER_PAGE,
                        "page", 1));
    }

    @Override
    public ProbeResult interpretProbe(TypedResponse response, ClaimedCommand command) {
        if (response.status() != 200 || response.arrayBody() == null) {
            return new ProbeResult.Unknown("http_" + response.status());
        }
        String marker = markerOf(command.operationId());
        Map<String, Object> matched = null;
        for (Map<String, Object> review : response.arrayBody()) {
            Object body = review.get("body");
            if (body != null && body.toString().contains(marker)) {
                String text = body.toString();
                if (text.indexOf(marker) != text.lastIndexOf(marker) || matched != null) {
                    return new ProbeResult.Unknown("ambiguous_review_marker");
                }
                matched = review;
            }
        }
        if (matched != null) {
            String text = matched.get("body").toString();
            return new ProbeResult.FoundWithContent(String.valueOf(matched.get("id")),
                    Objects.toString(matched.get("html_url"), null),
                    new Digest(Digests.sha256Hex(text)));
        }
        return new ProbeResult.NotFound();
    }

    @Override
    public Digest expectedContentDigest(ClaimedCommand command, Map<String, Object> payload) {
        return new Digest(Digests.sha256Hex(buildBody(command, payload)));
    }
}

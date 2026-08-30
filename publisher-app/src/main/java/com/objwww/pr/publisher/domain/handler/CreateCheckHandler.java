package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CREATE_CHECK：external_id = operation_id（本地幂等键即远端探针，§6.3）。
 * 探测窗口：按 head_sha 枚举该 SHA 下 checks，匹配 external_id。
 */
public final class CreateCheckHandler implements PublicationHandler {

    /** Check 名称白名单（§6.2 二次授权；gate 校验与本类翻译共用同一常量） */
    public static final Set<String> ALLOWED_CHECK_NAMES = Set.of("ai-code-review");

    static final int PROBE_PER_PAGE = 100;

    @Override
    public CommandType commandType() {
        return CommandType.CREATE_CHECK;
    }

    @Override
    public PublicationResourceType resourceType() {
        return PublicationResourceType.CHECK_RUN;
    }

    @Override
    public String resourceMarker(ClaimedCommand command) {
        return command.operationId().toString();
    }

    @Override
    public TypedWriteRequest buildRequest(ClaimedCommand command, Map<String, Object> payload) {
        String name = requiredText(payload, "name");
        String headSha = requiredText(payload, "head_sha");
        int findingCount = ((Number) payload.getOrDefault("finding_count", 0)).intValue();
        // conclusion 映射：M0 不阻塞合并——有发现 = neutral，零发现 = success
        String conclusion = findingCount > 0 ? "neutral" : "success";
        Map<String, Object> output = Map.of(
                "title", name,
                "summary", findingCount > 0
                        ? "AI 评审发现 " + findingCount + " 个问题，详见 Review。"
                        : "AI 评审未发现问题。");
        return new TypedWriteRequest(GitHubOperation.CREATE_CHECK_RUN,
                requiredText(payload, "repo"),
                Map.of("name", name,
                        "head_sha", headSha,
                        "external_id", command.operationId().toString(),
                        "status", "completed",
                        "conclusion", conclusion,
                        "output", output));
    }

    @Override
    public TypedOutcome interpret(TypedResponse response) {
        if (response.status() == 201 && response.objectBody() != null) {
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
            return StaleHeadClassifier.isHeadMismatch422(response)
                    ? TypedOutcome.staleHead(detailOf(response))
                    : TypedOutcome.failedTerminal("GITHUB_422", detailOf(response));
        }
        return TypedOutcome.failedTerminal("GITHUB_" + response.status(), detailOf(response));
    }

    @Override
    public TypedReadRequest buildProbe(ClaimedCommand command, Map<String, Object> payload) {
        return new TypedReadRequest(GitHubOperation.LIST_CHECKS_FOR_SHA,
                requiredText(payload, "repo"),
                Map.of("sha", requiredText(payload, "head_sha"),
                        "per_page", PROBE_PER_PAGE,
                        "page", 1));
    }

    @Override
    public ReconcileVerdict interpretProbe(TypedResponse response, ClaimedCommand command) {
        if (response.status() != 200 || response.objectBody() == null) {
            return ReconcileVerdict.unknown();
        }
        Object checkRuns = response.objectBody().get("check_runs");
        if (!(checkRuns instanceof List<?> runs)) {
            return ReconcileVerdict.unknown();
        }
        String probe = command.operationId().toString();
        for (Object item : runs) {
            if (item instanceof Map<?, ?> run && probe.equals(run.get("external_id"))) {
                return ReconcileVerdict.found(String.valueOf(run.get("id")),
                        Objects.toString(run.get("html_url"), null));
            }
        }
        return ReconcileVerdict.notFound(); // 本页未命中；窗口穷尽由执行器裁决
    }

    static String requiredText(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("payload 缺必需字段: " + field);
        }
        return value.toString();
    }

    static String detailOf(TypedResponse response) {
        return response.objectBody() == null ? null : Objects.toString(response.objectBody().get("message"), null);
    }
}

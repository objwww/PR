package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.GitHubOperation;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedOutcome;
import com.objwww.pr.shared.TypedReadRequest;
import com.objwww.pr.shared.TypedResponse;
import com.objwww.pr.shared.TypedWriteRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * UPDATE_CHECK：remote_identity = CHECK_RUN_ID（已存 GitHub check run id）。
 * 404 = 远端对象已消失——M0 策略不自动重建，记 MANUAL（§6.3）。
 */
public final class UpdateCheckHandler implements PublicationHandler {

    @Override
    public CommandType commandType() {
        return CommandType.UPDATE_CHECK;
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
        String checkRunId = CreateCheckHandler.requiredText(payload, "check_run_id");
        Map<String, Object> params = new HashMap<>();
        params.put("check_run_id", checkRunId);
        // 更新字段透传（control 铸造时保证合法；M0 control 尚不产生本型命令）
        for (String field : new String[]{"status", "conclusion", "output"}) {
            if (payload.containsKey(field)) {
                params.put(field, payload.get(field));
            }
        }
        return new TypedWriteRequest(GitHubOperation.UPDATE_CHECK_RUN,
                CreateCheckHandler.requiredText(payload, "repo"), params);
    }

    @Override
    public TypedOutcome interpret(TypedResponse response) {
        if (response.status() == 200 && response.objectBody() != null) {
            return TypedOutcome.confirmed(
                    String.valueOf(response.objectBody().get("id")),
                    (String) response.objectBody().get("html_url"));
        }
        if (response.status() == 404) {
            // 远端对象消失：M0 不自动重建，人工裁决（§6.3 策略表）
            return TypedOutcome.manual("REMOTE_NOT_FOUND", "check run 404");
        }
        if (response.isServerError()) {
            return TypedOutcome.serverRetryable("status=" + response.status());
        }
        if (response.status() == 401 || response.status() == 403) {
            return TypedOutcome.authFailed("status=" + response.status());
        }
        return TypedOutcome.failedTerminal("GITHUB_" + response.status(),
                CreateCheckHandler.detailOf(response));
    }

    @Override
    public TypedReadRequest buildProbe(ClaimedCommand command, Map<String, Object> payload) {
        return new TypedReadRequest(GitHubOperation.GET_CHECK_RUN,
                CreateCheckHandler.requiredText(payload, "repo"),
                Map.of("check_run_id", CreateCheckHandler.requiredText(payload, "check_run_id")));
    }

    @Override
    public ReconcileVerdict interpretProbe(TypedResponse response, ClaimedCommand command) {
        if (response.status() == 200 && response.objectBody() != null) {
            return ReconcileVerdict.found(String.valueOf(response.objectBody().get("id")),
                    (String) response.objectBody().get("html_url"));
        }
        if (response.status() == 404) {
            return ReconcileVerdict.manualPolicy(); // M0：不自动重建（§6.3）
        }
        return ReconcileVerdict.unknown();
    }
}

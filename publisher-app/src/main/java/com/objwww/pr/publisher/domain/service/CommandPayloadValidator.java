package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T3-A 第①步：命令 schema/白名单校验（§6.2 二次授权，EX-09，fail-closed E5）。
 * 纯函数；不合法者由 gate 落 FAILED_TERMINAL + SAFETY_REJECTED，绝不触网。
 */
final class CommandPayloadValidator {

    private CommandPayloadValidator() {
    }

    /**
     * @return 违例描述列表；空列表 = 合法
     */
    static List<String> violations(ClaimedCommand command, Map<String, Object> payload) {
        List<String> violations = new ArrayList<>();
        // payload.operation_id 必须与命令主键同源（§6.3：远端幂等探针即命令主键）
        Object payloadOpId = payload.get("operation_id");
        if (!command.operationId().toString().equals(Objects.toString(payloadOpId, null))) {
            violations.add("operation_id 与命令主键不一致");
        }
        requireText(payload, "repo", violations);
        switch (command.commandType()) {
            case CREATE_CHECK -> {
                requireText(payload, "head_sha", violations);
                Object name = payload.get("name");
                if (name == null || !CreateCheckHandler.ALLOWED_CHECK_NAMES.contains(name.toString())) {
                    violations.add("check 名称不在白名单: " + name);
                }
            }
            case UPDATE_CHECK -> requireText(payload, "check_run_id", violations);
            case PUBLISH_REVIEW -> {
                if (payload.get("pr_number") == null) {
                    violations.add("缺字段: pr_number");
                }
                requireText(payload, "commit_id", violations);
                // marker 必须与 operation_id 推导值一致（防铸造侧窜改探针）
                String expected = PublishReviewHandler.markerOf(command.operationId());
                if (!expected.equals(Objects.toString(payload.get("marker"), null))) {
                    violations.add("marker 与 operation_id 推导值不一致");
                }
            }
        }
        return violations;
    }

    private static void requireText(Map<String, Object> payload, String field, List<String> violations) {
        Object value = payload.get(field);
        if (value == null || value.toString().isBlank()) {
            violations.add("缺字段: " + field);
        }
    }
}

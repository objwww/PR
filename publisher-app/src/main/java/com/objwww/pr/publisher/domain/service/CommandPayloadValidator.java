package com.objwww.pr.publisher.domain.service;

import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * T3-A 第①步：命令 schema/白名单校验（§6.2 二次授权，EX-09，fail-closed E5）。
 * 纯函数；不合法者由 gate 落 FAILED_TERMINAL + SAFETY_REJECTED，绝不触网。
 *
 * <p>拒绝清单同时覆盖 repair payload 的覆盖注入面（评审 #23/AFT-16/EX-29）：
 * repair 命令复用原 payload 铸单，raw 寻址/凭证/installation 覆盖字段一律拒绝——
 * 唯一例外是 {@code installation_id} 预检声明（M1 SEC 加固：值由
 * {@link FencedPublicationExecutor} 与部署配置 fail-closed 比对，不作路由依据）。
 */
final class CommandPayloadValidator {

    private static final Set<String> FORBIDDEN_ADDRESS_OR_SECRET_KEYS = Set.of(
            "url", "uri", "api_url", "base_url", "method", "http_method", "endpoint",
            "token", "authorization", "password", "secret");

    /** 唯一合法的 installation 键：写前预检声明；其余 installation* 键一律视为覆盖注入 */
    private static final String INSTALLATION_PRECHECK_KEY = "installation_id";

    private CommandPayloadValidator() {
    }

    /**
     * @return 违例描述列表；空列表 = 合法
     */
    static List<String> violations(ClaimedCommand command, Map<String, Object> payload) {
        List<String> violations = new ArrayList<>();
        payload.keySet().stream()
                .filter(key -> isForbiddenKey(key.toLowerCase(java.util.Locale.ROOT)))
                .forEach(key -> violations.add("含禁止的寻址/凭证字段: " + key));
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

    private static boolean isForbiddenKey(String lowerKey) {
        return FORBIDDEN_ADDRESS_OR_SECRET_KEYS.contains(lowerKey)
                || (lowerKey.startsWith("installation")
                        && !INSTALLATION_PRECHECK_KEY.equals(lowerKey));
    }

    private static void requireText(Map<String, Object> payload, String field, List<String> violations) {
        Object value = payload.get(field);
        if (value == null || value.toString().isBlank()) {
            violations.add("缺字段: " + field);
        }
    }
}

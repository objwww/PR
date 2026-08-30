package com.objwww.pr.shared;

import java.util.Map;
import java.util.Objects;

/**
 * 类型化写请求（B15/§6.2，AFT-04 契约）：封闭的请求对象 = 操作枚举 + 仓库 + 参数表。
 * 刻意没有 raw url/method 字段——HTTP 动词与路径的拼装在 GitHubWriteAdapter 内唯一完成。
 * parameters 的键值约定由各 PublicationHandler 与 GitHubWriteAdapter 共享（如 head_sha、
 * external_id、check_run_id、commit_id、body）。
 */
public record TypedWriteRequest(
        GitHubOperation operation,
        String repositoryFullName,
        Map<String, Object> parameters) {

    public TypedWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(parameters, "parameters");
        if (!operation.isWrite()) {
            throw new IllegalArgumentException("非写操作不能构造 TypedWriteRequest: " + operation);
        }
        parameters = Map.copyOf(parameters);
    }
}

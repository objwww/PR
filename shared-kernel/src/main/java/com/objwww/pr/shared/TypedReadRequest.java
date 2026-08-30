package com.objwww.pr.shared;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 类型化探测读请求（§6.3 RemoteIdentityStrategy 的探针；AFT-04 契约同 TypedWriteRequest）。
 * 只允许读操作枚举；分页经 parameters 的 page/per_page 参数表达（参数 ≠ raw url）。
 */
public record TypedReadRequest(
        GitHubOperation operation,
        String repositoryFullName,
        Map<String, Object> parameters) {

    public TypedReadRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(parameters, "parameters");
        if (operation.isWrite()) {
            throw new IllegalArgumentException("写操作不能构造 TypedReadRequest: " + operation);
        }
        parameters = Map.copyOf(parameters);
    }

    /** 派生指定页码的探测请求（reconcile 翻页用；单资源探测 GET_CHECK_RUN 忽略分页） */
    public TypedReadRequest withPage(int page) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须 >= 1: " + page);
        }
        Map<String, Object> next = new HashMap<>(parameters);
        next.put("page", page);
        return new TypedReadRequest(operation, repositoryFullName, next);
    }
}

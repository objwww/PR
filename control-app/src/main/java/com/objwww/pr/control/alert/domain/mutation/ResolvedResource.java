package com.objwww.pr.control.alert.domain.mutation;

import java.util.Map;
import java.util.Objects;

/**
 * 权威解析后的资源身份（PB-B2，设计基线 §2.2 R6）：canonical 身份只来自
 * Resource Resolver 的权威清单——请求键（告警标签/业务 payload 里的 resource_key）
 * 只是查找输入，<b>不是</b>身份（B 组不变量：告警标签零授权效力）。
 */
public record ResolvedResource(
        String resourceUid,
        String requestedKey,
        String canonicalEnv,
        String canonicalTeam,
        String resourceKind,
        long resourceVersion,
        Map<String, String> labels) {

    public ResolvedResource {
        Objects.requireNonNull(resourceUid, "resourceUid");
        Objects.requireNonNull(requestedKey, "requestedKey");
        Objects.requireNonNull(canonicalEnv, "canonicalEnv");
        Objects.requireNonNull(canonicalTeam, "canonicalTeam");
        Objects.requireNonNull(resourceKind, "resourceKind");
        if (resourceVersion < 0) {
            throw new IllegalArgumentException("resourceVersion 不得为负");
        }
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}

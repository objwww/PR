package com.objwww.pr.control.application;

import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.OperationId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次发布请求的装配参数（application 层值对象）。
 * operationId 由调用方生成并嵌入 payload（CREATE_CHECK 的 external_id / PUBLISH_REVIEW 的
 * review body marker 都是它，§6.3），保证命令主键与远端幂等探针同源。
 */
public record PublicationRequest(
        OperationId operationId,
        UUID prSubjectId,
        UUID reviewRunId,
        UUID prRevisionId,
        String aggregateKey,
        CommandType commandType,
        String policyVersion,
        byte[] payload,
        List<DependencyEdge> dependencies) {

    /** 依赖边（M0 只用 REQUIRE_CONFIRMED：PUBLISH_REVIEW 依赖 CREATE_CHECK） */
    public record DependencyEdge(OperationId dependsOn, DependencyMode mode) {
        public DependencyEdge {
            Objects.requireNonNull(dependsOn, "dependsOn");
            Objects.requireNonNull(mode, "mode");
        }

        public static DependencyEdge requireConfirmed(OperationId dependsOn) {
            return new DependencyEdge(dependsOn, DependencyMode.REQUIRE_CONFIRMED);
        }
    }

    public PublicationRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(prSubjectId, "prSubjectId");
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(prRevisionId, "prRevisionId");
        Objects.requireNonNull(aggregateKey, "aggregateKey");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(payload, "payload");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload 不能为空");
        }
    }
}

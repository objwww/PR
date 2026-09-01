package com.objwww.pr.control.domain.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.domain.model.RepairCandidate;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.PublicationResourceType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 最新 CONFIRMED desired payload → 新 repair 命令；旧远端身份不继承。 */
public final class RepairCommandFactory {

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "url", "uri", "method", "endpoint", "token", "authorization", "password", "secret");

    public record Prepared(OperationId operationId, CommandType commandType, byte[] payload) {
    }

    private final ObjectMapper mapper;

    public RepairCommandFactory(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    public Prepared prepare(RepairCandidate candidate, byte[] latestPayload, byte[] basePayload) {
        try {
            Map<String, Object> source = mapper.readValue(latestPayload, new TypeReference<>() {});
            Map<String, Object> base = mapper.readValue(basePayload, new TypeReference<>() {});
            rejectForbidden(source);
            rejectForbidden(base);
            Map<String, Object> payload = new LinkedHashMap<>(base);
            payload.putAll(source);
            OperationId operationId = OperationId.random();
            payload.remove("remote_id");
            payload.remove("remote_url");
            payload.remove("check_run_id");
            payload.put("operation_id", operationId.toString());
            payload.put("repair_of_resource_id", candidate.resourceId().toString());
            payload.put("repair_request_id", candidate.requestId().toString());
            CommandType type = candidate.resourceType() == PublicationResourceType.CHECK_RUN
                    ? CommandType.CREATE_CHECK : CommandType.PUBLISH_REVIEW;
            if (type == CommandType.PUBLISH_REVIEW) {
                payload.put("marker", "<!-- ai-review:" + operationId + " -->");
            }
            return new Prepared(operationId, type, mapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IllegalArgumentException("repair desired payload 非法", e);
        }
    }

    private static void rejectForbidden(Map<String, Object> payload) {
        for (String key : payload.keySet()) {
            if (FORBIDDEN_KEYS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("repair payload 含禁止字段: " + key);
            }
        }
    }
}

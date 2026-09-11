package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor.ToolExecution;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EN-05 docker_ps/docker_inspect 执行器（§一 change/部署面 P0，只读边界钉死）：
 * 经 {@link DockerEngineTransport} 受限采集——env <b>只回键名永不回值</b>（secret 不进
 * 模型/报告，T09）；容器目标以<b>名称</b>表达且在<b>发出前</b>过 allowlist（越权目标
 * 零请求，T03/T09 同律）；ps 渲染只留 allowlist 容器（sidecar 等未授权容器零暴露）。
 * 字段投影白名单：状态/重启次数/镜像/OOM——非状态字段（挂载/网络细节）不采集。
 *
 * <p>不整体实现 ToolExecutor（execute 契约）：双入口方法同形，注册面以方法引用
 * 各注册为独立工具执行器。
 */
public class DockerInspectExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DockerEngineTransport transport;
    private final Set<String> containerAllowlist;

    public DockerInspectExecutor(DockerEngineTransport transport,
            Set<String> containerAllowlist) {
        if (containerAllowlist == null || containerAllowlist.isEmpty()) {
            throw new IllegalArgumentException("containerAllowlist 不得为空（fail-closed）");
        }
        this.transport = Objects.requireNonNull(transport, "transport 不得为 null");
        this.containerAllowlist = Set.copyOf(containerAllowlist);
    }

    /** docker.ps：→ allowlist 内容器状态清单；零命中 = NO_DATA（不伪造容器态） */
    public byte[] listContainers(ToolExecution execution) {
        JsonNode containers = parseArray(transport.get("/containers/json?all="
                + (Boolean.FALSE.equals(execution.validatedArgs().get("all")) ? "0" : "1")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode container : containers) {
            List<String> names = new ArrayList<>();
            container.path("Names").forEach(n -> names.add(
                    n.asText().startsWith("/") ? n.asText().substring(1) : n.asText()));
            if (names.stream().noneMatch(containerAllowlist::contains)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", container.path("Id").asText());
            row.put("names", names);
            row.put("image", container.path("Image").asText());
            row.put("state", container.path("State").asText());
            row.put("status", container.path("Status").asText());
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: allowlist 内无容器（空结果如实呈现）");
        }
        return render(rows, execution.resultLimitBytes());
    }

    /** docker.inspect：container（名称，前置 allowlist）→ 状态/重启/OOM/env 键名投影 */
    public byte[] inspectContainer(ToolExecution execution) {
        Object raw = execution.validatedArgs().get("container");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: container 必填");
        }
        String container = String.valueOf(raw);
        if (!containerAllowlist.contains(container)) {
            // 发出前拒绝（T03/T09）：越权目标零请求
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: container 越出 allowlist: " + container);
        }
        JsonNode detail = parseObject(transport.get("/containers/" + container + "/json"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", detail.path("Id").asText());
        row.put("name", detail.path("Name").asText());
        row.put("image", detail.path("Config").path("Image").asText());
        row.put("state", detail.path("State").path("Status").asText());
        row.put("running", detail.path("State").path("Running").asBoolean());
        row.put("restartCount", detail.path("State").path("RestartCount").asLong());
        row.put("oomKilled", detail.path("State").path("OOMKilled").asBoolean());
        row.put("startedAt", detail.path("State").path("StartedAt").asText());
        List<String> envKeys = new ArrayList<>();
        detail.path("Config").path("Env").forEach(e -> {
            String entry = e.asText();
            int eq = entry.indexOf('=');
            // 只回键名：env 值（含 secret）永不回传（§一只读边界）
            envKeys.add(eq >= 0 ? entry.substring(0, eq) : entry);
        });
        row.put("envKeys", envKeys);
        return render(List.of(row), execution.resultLimitBytes());
    }

    // ------------------------------------------------------------------ 内部

    private static JsonNode parseArray(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        "容器清单响应不可解析（临时故障，可重试）");
            }
            return root;
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "容器清单响应不可解析（临时故障，可重试）");
        }
    }

    private static JsonNode parseObject(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) {
                throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        "容器检视响应不可解析（临时故障，可重试）");
            }
            return root;
        } catch (IOException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "容器检视响应不可解析（临时故障，可重试）");
        }
    }

    /** 统一形状渲染（status=success + data.result），字节上限即断 */
    private static byte[] render(List<Map<String, Object>> rows, long limitBytes) {
        long cap = Math.max(1, limitBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (var gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeBooleanField("truncated", false);
            gen.writeFieldName("result");
            gen.writeStartArray();
            for (Map<String, Object> row : rows) {
                gen.writeObject(row);
                gen.flush();
                if (out.size() > cap) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("docker 工具结果序列化失败", e);
        }
        if (out.size() > cap) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
        }
        return out.toByteArray();
    }
}

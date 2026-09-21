package com.objwww.pr.control.alert.application.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.tool.ArgsNormalizer;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationIdentity;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.shared.Digest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具调用派发面（EN-06）：server_id+原始 tool_name 身份（§2.2，不碰撞、审计可
 * 定位实际 server——账本工具名 {@code mcp:<server>:<tool>}，M03）；统一账本 PENDING
 * 先行 → 终态 CAS（M01）；isError/畸形/缺 content 不因 HTTP200 记成功，错误分类+
 * 有界输出完整（M08）；描述零提权——工具描述只透传展示，从不参与权限判定或参数
 * 改写（M09）；TTL 过期后旧 schema 不误用（M05）。派发经资格领取（与 disable 串行，
 * M06），在飞计数支撑 drain（M07）。D02 结果契约：structured-only 结果合法传递
 * （确定性 JSON 投影，不 NPE）；不支持内容类型明确能力错误；isError 按参数/远端
 * 分类（401/403 归 AUTH_FAILED 不盲目重试）；输出字节预算按实际向下游传递的
 * 序列化内容计；outputSchema 最小校验面见 {@link #validateOutputSchema}。
 * D02-7（官方 client conformance 基线）本次未做，如实标注。
 */
public class McpToolInvoker {

    public record McpInvokeResult(String serverName, String toolName, String content,
            Map<String, Object> structuredContent, String resultSelection) {
    }

    /** D02-3 结果选择策略：结构化优先（确定性 JSON 投影）/仅文本透传 */
    static final String SELECTION_STRUCTURED_JSON = "STRUCTURED_JSON_PROJECTION";
    static final String SELECTION_TEXT = "TEXT";
    /** D02-5 模型可修正错误正文的脱敏限长上限 */
    private static final int MAX_ERROR_EXCERPT = 200;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final McpMountManager manager;
    private final RcaToolInvocationLedger ledger;
    private final int maxResultBytes;

    public McpToolInvoker(McpMountManager manager, RcaToolInvocationLedger ledger,
            int maxResultBytes) {
        this.manager = manager;
        this.ledger = ledger;
        this.maxResultBytes = maxResultBytes;
    }

    public McpInvokeResult invoke(String serverName, String toolName,
            Map<String, Object> arguments, UUID runId, UUID taskId, UUID attemptId,
            long callSeq) {
        McpMountManager.MountedServer entry = manager.ensureFresh(serverName);
        McpServerClient.ToolDescriptor tool = entry.tools().stream()
                .filter(t -> toolName.equals(t.name())).findFirst().orElse(null);
        if (tool == null) {
            throw new ToolControlPlaneException(ToolControlReason.UNKNOWN_TOOL,
                    "UNKNOWN_TOOL: server " + serverName + " 无工具 " + toolName
                            + "（schema 以最近一次校验为准，旧 schema 不误用）");
        }
        UUID operationId = UUID.randomUUID();
        String auditTool = "mcp:" + serverName + ":" + toolName;
        ledger.open(new InvocationIdentity(operationId, runId, taskId, attemptId, callSeq,
                auditTool, "gen" + entry.record().generation(), actionDigest(arguments)));
        try {
            manager.enterInvocation(serverName);
        } catch (ToolControlPlaneException e) {
            // BA-190（W3）：并发闸拒绝的具体消息落 reason_detail（事后可考）
            ledger.fail(operationId, ToolInvocationState.FAILED, ToolReasonCode.POLICY_DENIED,
                    e.getMessage());
            throw e;
        }
        try {
            McpServerClient.McpToolResult result =
                    entry.client().callTool(toolName, arguments);
            if (result.error()) {
                // D02-5：isError 保存脱敏、限长、模型可理解的错误正文并分类——
                // 参数错误归 INVALID_ARGS/INVALID_INPUT（有界修正，不盲目重试）；
                // 其余归 REMOTE_UNAVAILABLE/REMOTE_5XX（瞬态由统一重试预算处理）。
                String excerpt = errorExcerpt(result.textContent());
                if (looksLikeArgumentError(result.textContent())) {
                    String detail = "INVALID_ARGS: MCP 上游拒绝工具参数（修正 args 后重发）: "
                            + excerpt;
                    ledger.fail(operationId, ToolInvocationState.FAILED,
                            ToolReasonCode.INVALID_INPUT, detail);
                    throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS, detail);
                }
                throw settle(operationId, ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        ToolReasonCode.REMOTE_5XX,
                        "REMOTE_UNAVAILABLE: MCP 上游返回 isError（HTTP 200 不代表成功）: "
                                + excerpt);
            }
            if (result.textContent() == null && !result.structuredPresent()
                    && !result.unsupportedContentTypes().isEmpty()) {
                // D02-3：结果仅含不支持的非文本类型——明确能力错误，不伪装远程故障、不进重试循环
                String detail = "UNSUPPORTED_CONTENT: MCP 结果仅含不支持的内容类型 "
                        + result.unsupportedContentTypes() + "（支持面: text/structuredContent）";
                ledger.fail(operationId, ToolInvocationState.FAILED,
                        ToolReasonCode.POLICY_DENIED, detail);
                throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED, detail);
            }
            if (result.malformed()) {
                throw settle(operationId, ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        ToolReasonCode.REMOTE_5XX,
                        "REMOTE_UNAVAILABLE: MCP 上游结果畸形或缺 content（不因 HTTP200 记成功）");
            }
            String selected = selectContent(result);
            byte[] content = selected.getBytes(StandardCharsets.UTF_8);
            if (content.length > maxResultBytes) {
                // BA-190（W3）：超限事实落 reason_detail（D02-4：按实际向下游传递的
                // 序列化内容计——结构化结果计其确定性 JSON 投影，与文本同一字节预算）
                ledger.fail(operationId, ToolInvocationState.FAILED,
                        ToolReasonCode.TRANSPORT_UNKNOWN,
                        "RESULT_OVERSIZE: MCP 结果 " + content.length + "B 超限 "
                                + maxResultBytes + "B");
                throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                        "RESULT_OVERSIZE: MCP 结果 " + content.length + "B 超限 "
                                + maxResultBytes + "B");
            }
            String schemaViolation = validateOutputSchema(tool.outputSchema(), result);
            if (schemaViolation != null) {
                // D02-4：schema 校验失败 = 上游契约缺陷（REMOTE_5XX 归因），与网络失败
                // （TRANSPORT_UNKNOWN）分类分开；QUERY_FAILED 终止族不进重试循环
                String detail = "OUTPUT_SCHEMA_VIOLATION: MCP 结果违反工具声明的 outputSchema（"
                        + schemaViolation + "）";
                ledger.fail(operationId, ToolInvocationState.FAILED,
                        ToolReasonCode.REMOTE_5XX, detail);
                throw new ToolControlPlaneException(ToolControlReason.QUERY_FAILED, detail);
            }
            ledger.succeed(operationId);
            return new McpInvokeResult(serverName, toolName, selected,
                    result.structuredContent(),
                    result.structuredPresent() ? SELECTION_STRUCTURED_JSON : SELECTION_TEXT);
        } catch (McpServerClient.AuthException e) {
            // D02-5：401/403 不进入盲目重试——AUTH_FAILED 终止族，无凭据轮换回退（M10 同律）
            String detail = "AUTH_FAILED: MCP server " + serverName
                    + " 调用鉴权被拒（401/403）——无回退，不盲目重试";
            ledger.fail(operationId, ToolInvocationState.FAILED, ToolReasonCode.AUTH_FAILED,
                    detail);
            throw new ToolControlPlaneException(ToolControlReason.AUTH_FAILED, detail);
        } catch (ToolControlPlaneException | ToolModelVisibleException e) {
            throw e;
        } catch (Exception e) {
            throw settle(operationId, ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    ToolReasonCode.TRANSPORT_UNKNOWN,
                    "REMOTE_UNAVAILABLE: MCP 调用失败（" + e.getClass().getSimpleName() + "）");
        } finally {
            manager.exitInvocation(serverName);
        }
    }

    /**
     * D02-3 结果选择策略：有结构化结果 → 结构化为准，向只接收文本的下游提供确定性
     * JSON 投影（键序全层排序，同一内容投影恒定）；文本与结构化并存不拼接成含糊文本
     * （策略落 {@link McpInvokeResult#resultSelection}）；仅文本 → 原文透传。
     */
    private static String selectContent(McpServerClient.McpToolResult result) {
        if (result.structuredPresent()) {
            try {
                return JSON.writeValueAsString(result.structuredContent());
            } catch (Exception e) {
                throw new IllegalStateException("MCP 结构化结果投影失败", e);
            }
        }
        return result.textContent();
    }

    /**
     * D02-4 最小 outputSchema 校验面（项目未安装 JSON Schema 校验器，不引新依赖——
     * 完整校验能力缺席，如实标注）：仅对声明 type=object 的 schema 校验结构化输出
     * 的 required 字段齐全；空对象 {} 在无 required 约束下合法（不因 Map 为空认定畸形）。
     */
    private static String validateOutputSchema(Map<String, Object> outputSchema,
            McpServerClient.McpToolResult result) {
        if (outputSchema == null || outputSchema.isEmpty() || !result.structuredPresent()) {
            return null;
        }
        Object type = outputSchema.get("type");
        if (type != null && !"object".equals(type)) {
            return null;
        }
        if (outputSchema.get("required") instanceof List<?> required) {
            for (Object field : required) {
                if (field != null && !result.structuredContent().containsKey(String.valueOf(field))) {
                    return "缺必需字段 " + field;
                }
            }
        }
        return null;
    }

    /** D02-5 错误正文脱敏限长：压平空白、截断，不含堆栈；无正文时明示 */
    private static String errorExcerpt(String text) {
        if (text == null || text.isBlank()) {
            return "（上游未附错误正文）";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_EXCERPT
                ? flat : flat.substring(0, MAX_ERROR_EXCERPT) + "…";
    }

    /** D02-5 参数错误启发式归类：正文指出参数字段问题 → 有界修正路径（INVALID_ARGS） */
    private static boolean looksLikeArgumentError(String text) {
        if (text == null) {
            return false;
        }
        String flat = text.toLowerCase(java.util.Locale.ROOT);
        return flat.contains("invalid argument") || flat.contains("invalid_arguments")
                || flat.contains("invalid param") || flat.contains("invalid input")
                || flat.contains("missing required") || flat.contains("is required")
                || flat.contains("unknown field") || flat.contains("validation failed");
    }

    /** 结算 FAILED 后返回待抛的模型可见族异常（账本只结算一次）；
     *  BA-190（W3）：固定脱敏文案随账落 reason_detail */
    private ToolModelVisibleException settle(UUID operationId, ToolModelVisibleReason reason,
            ToolReasonCode ledgerReason, String message) {
        ledger.fail(operationId, ToolInvocationState.FAILED, ledgerReason, message);
        return new ToolModelVisibleException(reason, message);
    }

    /**
     * 参数 canonical JSON（键全层排序）的 sha256，作账本 action_digest。MC24 裁定
     * （P0-2 ③）：与主路径 ActionDigest <b>并列不收敛</b>——本 digest 是 MCP 面
     * 账本审计指纹（无 replay 匹配职责），收敛需引入 envelope 字段（schema/
     * timeRange/inputDigest）改变既有行值语义；防漂移靠同一规范化器接入
     * （{@link ArgsNormalizer}，空白差异不再造成审计指纹漂移）。
     */
    private static String actionDigest(Map<String, Object> arguments) {
        try {
            return Digest.sha256Of(JSON.writeValueAsString(ArgsNormalizer.normalize(
                    arguments == null ? Map.of() : arguments))).value();
        } catch (Exception e) {
            return Digest.sha256Of(String.valueOf(arguments)).value();
        }
    }
}

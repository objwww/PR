package com.objwww.pr.control.alert.application.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具调用派发面（EN-06）：server_id+原始 tool_name 身份（§2.2，不碰撞、审计可
 * 定位实际 server——账本工具名 {@code mcp:<server>:<tool>}，M03）；统一账本 PENDING
 * 先行 → 终态 CAS（M01）；isError/畸形/缺 content 不因 HTTP200 记成功，错误分类+
 * 有界输出完整（M08）；描述零提权——工具描述只透传展示，从不参与权限判定或参数
 * 改写（M09）；TTL 过期后旧 schema 不误用（M05）。派发经资格领取（与 disable 串行，
 * M06），在飞计数支撑 drain（M07）。
 */
public class McpToolInvoker {

    public record McpInvokeResult(String serverName, String toolName, String content) {
    }

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
        if (entry.tools().stream().noneMatch(tool -> toolName.equals(tool.name()))) {
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
            ledger.fail(operationId, ToolInvocationState.FAILED, ToolReasonCode.POLICY_DENIED);
            throw e;
        }
        try {
            McpServerClient.McpToolResult result =
                    entry.client().callTool(toolName, arguments);
            if (result.error()) {
                throw settle(operationId, ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        ToolReasonCode.REMOTE_5XX,
                        "REMOTE_UNAVAILABLE: MCP 上游返回 isError（HTTP 200 不代表成功）");
            }
            if (result.malformed()) {
                throw settle(operationId, ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                        ToolReasonCode.REMOTE_5XX,
                        "REMOTE_UNAVAILABLE: MCP 上游结果畸形或缺 content（不因 HTTP200 记成功）");
            }
            byte[] content = result.textContent().getBytes(StandardCharsets.UTF_8);
            if (content.length > maxResultBytes) {
                ledger.fail(operationId, ToolInvocationState.FAILED,
                        ToolReasonCode.TRANSPORT_UNKNOWN);
                throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                        "RESULT_OVERSIZE: MCP 结果 " + content.length + "B 超限 "
                                + maxResultBytes + "B");
            }
            ledger.succeed(operationId);
            return new McpInvokeResult(serverName, toolName, result.textContent());
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

    /** 结算 FAILED 后返回待抛的模型可见族异常（账本只结算一次） */
    private ToolModelVisibleException settle(UUID operationId, ToolModelVisibleReason reason,
            ToolReasonCode ledgerReason, String message) {
        ledger.fail(operationId, ToolInvocationState.FAILED, ledgerReason);
        return new ToolModelVisibleException(reason, message);
    }

    /** 参数 canonical JSON（键全层排序）的 sha256，作账本 action_digest */
    private static String actionDigest(Map<String, Object> arguments) {
        try {
            return Digest.sha256Of(JSON.writeValueAsString(arguments == null
                    ? Map.of() : arguments)).value();
        } catch (Exception e) {
            return Digest.sha256Of(String.valueOf(arguments)).value();
        }
    }
}

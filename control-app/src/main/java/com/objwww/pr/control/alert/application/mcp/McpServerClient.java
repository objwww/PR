package com.objwww.pr.control.alert.application.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP server 客户端端口（EN-06）：机制面（挂载管理/调用派发）与 SDK 面解耦——
 * 机制测试用假件，真实现（{@code infrastructure.mcp.SdkMcpServerClientFactory}）
 * 包 MCP Java SDK 2.0.0 编程式 client（spike：En06McpSdkSpikeTest）。
 * {@link AuthException} 由真实现按 401/403 归类，供挂载面有界重试与 AUTH_FAILED
 * 终止（M10）；异常文案一律脱敏（不含凭据/原始报文）。
 */
public interface McpServerClient extends AutoCloseable {

    /** initialize 握手（发布前网络校验） */
    void connect();

    /** 工具清单 = schema 校验面（描述只透传展示，不参与权限判定——M09） */
    List<ToolDescriptor> listTools();

    /** 调用上游工具；结果三面由 {@link McpToolResult} 显式承载（M08） */
    McpToolResult callTool(String toolName, Map<String, Object> arguments);

    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    }

    /**
     * 上游结果三面（M08：不能因 HTTP200 记成功）：error=isError；
     * malformed=零文本零结构（缺 content/畸形）；正常 = 有 text。
     */
    record McpToolResult(boolean error, String textContent, boolean structuredPresent) {

        public static McpToolResult text(String content) {
            return new McpToolResult(false, content, false);
        }

        public boolean malformed() {
            return !error && textContent == null && !structuredPresent;
        }
    }

    /** 鉴权失败归类（凭证轮换失败/过期——M10 有界重试后 AUTH_FAILED 终止） */
    class AuthException extends RuntimeException {

        public AuthException(String message) {
            super(message);
        }
    }
}

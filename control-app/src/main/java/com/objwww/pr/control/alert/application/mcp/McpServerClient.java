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

    /**
     * D02-6 接线端口：注册 tools/list_changed 通知回调（真实 SDK 路径在 connect()
     * 装配 toolsChangeConsumer 后生效；通知只触发候选重检，不自动授权新增工具）。
     * default no-op = 机制测试假件零漂移（假件用 McpMountManager.onToolsChanged 直调钉机制）。
     */
    default void setToolsChangedHandler(Runnable handler) {
    }

    /**
     * 工具描述（schema 校验面）。outputSchema：D02-4 从工具发现映射进受控注册信息
     * （可选——上游未声明时为 null；描述只透传展示，不参与权限判定——M09）。
     */
    record ToolDescriptor(String name, String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema) {

        /** 兼容无 outputSchema 声明的发现面/假件 */
        public ToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, null);
        }
    }

    /**
     * 上游结果显式契约（D02-1：structuredPresent 布尔扩展为实际结构化 payload，
     * structured-only 结果不再因空文本 NPE）：error=isError；structuredContent=
     * 结构化 payload 本体（空对象 {} 是合法结构化结果，不因 Map 为空认定畸形）；
     * unsupportedContentTypes=SDK 边界识别的非文本内容类型（image 等——能力缺口
     * 由派发面明确拒绝，不伪装远程故障）；malformed=零文本零结构零不支持类型
     * （缺 content/畸形）。正常 = 有 text 或有 structuredContent。
     */
    record McpToolResult(boolean error, String textContent, Map<String, Object> structuredContent,
            List<String> unsupportedContentTypes) {

        public McpToolResult {
            structuredContent = structuredContent == null ? null : Map.copyOf(structuredContent);
            unsupportedContentTypes = unsupportedContentTypes == null
                    ? List.of() : List.copyOf(unsupportedContentTypes);
        }

        /** 兼容旧布尔契约：structuredPresent=true → 空对象结构（合法，不畸形） */
        public McpToolResult(boolean error, String textContent, boolean structuredPresent) {
            this(error, textContent, structuredPresent ? Map.of() : null, List.of());
        }

        public static McpToolResult text(String content) {
            return new McpToolResult(false, content, null, List.of());
        }

        public boolean structuredPresent() {
            return structuredContent != null;
        }

        public boolean malformed() {
            return !error && textContent == null && structuredContent == null
                    && unsupportedContentTypes.isEmpty();
        }
    }

    /** 鉴权失败归类（凭证轮换失败/过期——M10 有界重试后 AUTH_FAILED 终止） */
    class AuthException extends RuntimeException {

        public AuthException(String message) {
            super(message);
        }
    }
}

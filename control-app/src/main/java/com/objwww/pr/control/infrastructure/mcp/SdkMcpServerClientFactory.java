package com.objwww.pr.control.infrastructure.mcp;

import com.objwww.pr.control.alert.application.mcp.McpMountManager.McpClientFactory;
import com.objwww.pr.control.alert.application.mcp.McpServerClient;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository.ServerSpec;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP Java SDK 2.0.0 编程式 client 适配（EN-06 spike 直迁：En06McpSdkSpikeTest
 * 验证 initialize→tools/list→tools/call 全链）。Streamable-HTTP 传输、握手懒建立
 * （openConnectionOnStartup=false，connect() 显式握手）；异常按 401/403 归类
 * {@link McpServerClient.AuthException}（M10），文案一律脱敏（只留类名语义，不透传
 * 原始报文/凭据）。headers_ref = env 变量名（引用而非凭证值），非空时以
 * Authorization: Bearer 挂静态请求头。
 */
public class SdkMcpServerClientFactory implements McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SdkMcpServerClientFactory.class);

    private final Duration requestTimeout;
    private final Duration connectTimeout;

    public SdkMcpServerClientFactory(Duration requestTimeout, Duration connectTimeout) {
        this.requestTimeout = requestTimeout;
        this.connectTimeout = connectTimeout;
    }

    @Override
    public McpServerClient create(ServerSpec spec) {
        if (!"streamable_http".equals(spec.transport())) {
            throw new IllegalArgumentException(
                    "SdkMcpServerClientFactory 仅支持 streamable_http（stdio 归运维预装窗）");
        }
        return new SdkClient(spec);
    }

    private final class SdkClient implements McpServerClient {

        private final ServerSpec spec;
        private volatile McpSyncClient client;

        private SdkClient(ServerSpec spec) {
            this.spec = spec;
        }

        @Override
        public void connect() {
            URI uri = URI.create(spec.endpoint());
            String base = uri.getScheme() + "://" + uri.getRawAuthority();
            String path = (uri.getRawPath() == null || uri.getRawPath().isBlank())
                    ? "/mcp" : uri.getRawPath();
            HttpClientStreamableHttpTransport.Builder transport =
                    HttpClientStreamableHttpTransport.builder(base)
                            .endpoint(path)
                            .resumableStreams(false)
                            .openConnectionOnStartup(false)
                            .connectTimeout(connectTimeout);
            String token = resolveHeaderToken(spec.headersRef());
            if (token != null && !token.isBlank()) {
                transport.requestBuilder(java.net.http.HttpRequest.newBuilder()
                        .header("Authorization", "Bearer " + token));
            }
            try {
                client = McpClient.sync(transport.build())
                        .requestTimeout(requestTimeout)
                        .initializationTimeout(requestTimeout)
                        .clientInfo(new McpSchema.Implementation("control-app", "1"))
                        .build();
                client.initialize();
            } catch (Exception e) {
                client = null;
                throw classify(e);
            }
        }

        @Override
        public List<ToolDescriptor> listTools() {
            try {
                return client.listTools().tools().stream()
                        .map(tool -> new ToolDescriptor(tool.name(),
                                tool.description() == null ? "" : tool.description(),
                                tool.inputSchema() == null
                                        ? Map.of("type", "object") : tool.inputSchema()))
                        .toList();
            } catch (Exception e) {
                throw classify(e);
            }
        }

        @Override
        public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, arguments));
            Object structuredContent = result.structuredContent();
            boolean structured = structuredContent != null
                    && (!(structuredContent instanceof Map<?, ?> map) || !map.isEmpty());
            StringBuilder text = null;
            for (McpSchema.Content item : result.content()) {
                if (item instanceof McpSchema.TextContent textContent) {
                    text = text == null ? new StringBuilder() : text;
                    text.append(textContent.text());
                }
            }
            return new McpToolResult(Boolean.TRUE.equals(result.isError()),
                    text == null ? null : text.toString(), structured);
        }

        @Override
        public void close() {
            McpSyncClient current = client;
            client = null;
            if (current == null) {
                return;
            }
            try {
                current.closeGracefully();
            } catch (Exception e) {
                log.debug("MCP client 关闭异常（忽略）: {}", e.getClass().getSimpleName());
            }
        }

        /** headers_ref = env 变量名（引用而非凭证值）；变量缺失/空 → 不带头（fail-closed） */
        private String resolveHeaderToken(String headersRef) {
            if (headersRef == null || headersRef.isBlank()) {
                return null;
            }
            return System.getenv(headersRef);
        }

        private RuntimeException classify(Exception cause) {
            String message = String.valueOf(cause.getMessage()).toLowerCase();
            if (message.contains("401") || message.contains("403")
                    || message.contains("unauthorized") || message.contains("forbidden")) {
                return new McpServerClient.AuthException("MCP 握手/调用鉴权被拒（401/403）");
            }
            return new IllegalStateException("MCP 调用失败: "
                    + cause.getClass().getSimpleName());
        }
    }
}

package com.objwww.pr.control.infrastructure.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-06 固定 SDK spike（§2.2：编程式 client 兼容性以实际构建记录为准）：MCP Java SDK
 * <b>2.0.0</b>（io.modelcontextprotocol.sdk:mcp → mcp-core + mcp-json-jackson3，
 * reactor-core 钉 3.7.5）在同一 JVM 内经 {@link HttpClientStreamableHttpTransport}
 * 对 WireMock Streamable-HTTP 端点完成 initialize 握手 → tools/list → tools/call
 * 全链——证明官方 SDK 编程式 client 可程序化构建/调用/关闭（§2.4 否决条件不触发，
 * 动态挂载可上）。M04 挂点（toolsChangeConsumer 回调面）同轮验证。
 */
class En06McpSdkSpikeTest {

    private static final WireMockServer WIREMOCK = new WireMockServer(0);

    private McpSyncClient client;
    private final List<List<McpSchema.Tool>> toolsChangeEvents = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stop() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void stubMcpEndpoint() {
        WIREMOCK.resetAll();
        // Streamable-HTTP：POST JSON-RPC 请求/响应（id 为字符串，模板回显带引号）
        WIREMOCK.stubFor(post(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$[?(@.method == 'initialize')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Mcp-Session-Id", "spike-session-1")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"protocolVersion":"{{jsonPath request.body '$.params.protocolVersion'}}",
                                  "capabilities":{"tools":{"listChanged":true}},
                                  "serverInfo":{"name":"spike-mcp","title":"Spike","version":"1.0.0"}}}""")));
        WIREMOCK.stubFor(post(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$[?(@.method == 'notifications/initialized')]"))
                .willReturn(aResponse().withStatus(202)));
        WIREMOCK.stubFor(post(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/list')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"tools":[
                                   {"name":"get_alerts","description":"list firing alerts",
                                    "inputSchema":{"type":"object","properties":{},"required":[]}}]}}""")));
        WIREMOCK.stubFor(post(urlEqualTo("/mcp"))
                .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/call')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"content":[{"type":"text","text":"alerts: 1 firing"}],
                                  "isError":false}}""")));
        // 服务端→客户端 SSE 流不提供（GET 405 = 本会话无服务端主动通知面）
        WIREMOCK.stubFor(get(urlEqualTo("/mcp"))
                .willReturn(aResponse().withStatus(405)));
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    private McpSyncClient client() {
        client = McpClient.sync(HttpClientStreamableHttpTransport
                        .builder(WIREMOCK.baseUrl())
                        .endpoint("/mcp")
                        .resumableStreams(false)
                        .openConnectionOnStartup(false)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .initializationTimeout(Duration.ofSeconds(5))
                .clientInfo(new McpSchema.Implementation("control-app-spike", "1"))
                .toolsChangeConsumer(toolsChangeEvents::add)
                .build();
        return client;
    }

    @Test
    @DisplayName("spike：initialize 握手 → tools/list → tools/call 全链（SDK 2.0.0 编程式 client）")
    void fullHandshakeListAndCallRoundTrip() {
        McpSyncClient spike = client();

        McpSchema.InitializeResult init;
        try {
            init = spike.initialize();
        } catch (RuntimeException e) {
            System.out.println("==== SERVE EVENTS ====");
            WIREMOCK.getAllServeEvents().forEach(ev -> System.out.println(
                    ev.getRequest().getMethod() + " " + ev.getRequest().getUrl() + " -> "
                            + (ev.getResponse() == null ? "no-response" : ev.getResponse().getStatus())
                            + " body=" + ev.getRequest().getBodyAsString()));
            System.out.println("==== UNMATCHED ====");
            WIREMOCK.findAllUnmatchedRequests().forEach(r -> System.out.println(
                    r.getMethod() + " " + r.getUrl() + " body=" + r.getBodyAsString()));
            throw e;
        }
        assertThat(spike.isInitialized()).as("握手完成").isTrue();
        assertThat(init.serverInfo().name()).isEqualTo("spike-mcp");
        assertThat(init.protocolVersion()).isNotBlank();

        McpSchema.ListToolsResult tools = spike.listTools();
        assertThat(tools.tools()).as("schema 校验后可见").hasSize(1);
        assertThat(tools.tools().get(0).name()).isEqualTo("get_alerts");
        assertThat(tools.tools().get(0).description()).contains("alerts");

        McpSchema.CallToolResult call = spike.callTool(
                new McpSchema.CallToolRequest("get_alerts", Map.of()));
        assertThat(call.isError()).as("不因 HTTP 200 误判成功面").isFalse();
        assertThat(call.content()).hasSize(1);
        assertThat(((McpSchema.TextContent) call.content().get(0)).text())
                .contains("1 firing");
        assertThat(WIREMOCK.getAllServeEvents().stream()
                .filter(e -> String.valueOf(e.getRequest().getMethod())
                        .equalsIgnoreCase("POST")).count())
                .as("initialize+initialized+list+call 四请求").isEqualTo(4);
    }

    @Test
    @DisplayName("M04 挂点：toolsChangeConsumer 回调面在册（list_changed 只触发候选重检）")
    void toolsChangeConsumerHookRegistered() {
        McpSyncClient spike = client();
        spike.initialize();
        assertThat(spike.getServerCapabilities().tools()).as("tools 能力在握").isNotNull();
        // 回调注册面：SDK 2.0.0 SyncSpec.toolsChangeConsumer 编译+装配通过即为本钉；
        // 事件本体由 server 经 SSE 通知（本 spike 端点 405，无通知面——机制面测试
        // 在 McpMountManagerTest 用可注入端口钉，此处只钉 SDK 挂点真实存在）。
        assertThat(toolsChangeEvents).isEmpty();
        assertThat(spike.listTools()).isNotNull();
    }
}

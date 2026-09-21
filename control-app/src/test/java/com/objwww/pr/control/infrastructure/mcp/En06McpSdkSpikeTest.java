package com.objwww.pr.control.infrastructure.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.alert.application.mcp.McpMountManager;
import com.objwww.pr.control.alert.application.mcp.McpServerClient;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository;
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

    // ------------------------------------------------------------ D02：SDK 边界映射面

    private McpServerClient sdkAdapter() {
        return new SdkMcpServerClientFactory(Duration.ofSeconds(5), Duration.ofSeconds(2))
                .create(new McpServerRegistryRepository.ServerSpec("wire", "streamable_http",
                        WIREMOCK.baseUrl() + "/mcp", List.of(), null, true));
    }

    private void stubCallResult(String toolName, String resultJson) {
        WIREMOCK.stubFor(post(urlEqualTo("/mcp")).atPriority(1)
                .withRequestBody(matchingJsonPath(
                        "$[?(@.method == 'tools/call' && @.params.name == '" + toolName + "')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\","
                                + "\"result\":" + resultJson + "}")));
    }

    @Test
    @DisplayName("MCP-01/04（SDK 面）D02-2：structured-only 完整传出、多文本块 \\n 分隔、仅图像显式登记不支持类型")
    void sdkAdapterMapsStructuredTextSeparatorAndUnsupportedContent() throws Exception {
        stubCallResult("structured_tool",
                "{\"content\":[],\"structuredContent\":{\"count\":3},\"isError\":false}");
        stubCallResult("multi_text",
                "{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}],"
                        + "\"isError\":false}");
        stubCallResult("image_only",
                "{\"content\":[{\"type\":\"image\",\"data\":\"aGk=\",\"mimeType\":\"image/png\"}],"
                        + "\"isError\":false}");
        McpServerClient adapter = sdkAdapter();
        adapter.connect();

        McpServerClient.McpToolResult structured = adapter.callTool("structured_tool", Map.of());
        assertThat(structured.structuredPresent()).isTrue();
        assertThat(structured.structuredContent()).containsEntry("count", 3);
        assertThat(structured.textContent()).isNull();
        assertThat(structured.malformed()).as("structured-only 合法，不当畸形").isFalse();

        McpServerClient.McpToolResult multiText = adapter.callTool("multi_text", Map.of());
        assertThat(multiText.textContent()).as("文本块按显式分隔符组合").isEqualTo("a\nb");

        McpServerClient.McpToolResult imageOnly = adapter.callTool("image_only", Map.of());
        assertThat(imageOnly.malformed()).isFalse();
        assertThat(imageOnly.unsupportedContentTypes())
                .as("不支持类型显式登记，不吞掉不 NPE").containsExactly("image");
        adapter.close();
    }

    @Test
    @DisplayName("MCP-04（SDK 面）D02-2：null content 在 SDK 边界归一化——畸形判定，不发生未分类 NPE")
    void sdkAdapterNormalizesNullContentAtBoundary() throws Exception {
        stubCallResult("null_content", "{\"isError\":false}");
        McpServerClient adapter = sdkAdapter();
        adapter.connect();

        McpServerClient.McpToolResult result = adapter.callTool("null_content", Map.of());

        assertThat(result.malformed()).as("零文本零结构=畸形（不因 HTTP200 记成功）").isTrue();
        adapter.close();
    }

    @Test
    @DisplayName("D02-4：outputSchema 从工具发现映射进受控注册信息（ToolDescriptor）")
    void listToolsMapsOutputSchemaIntoToolDescriptor() throws Exception {
        WIREMOCK.stubFor(post(urlEqualTo("/mcp")).atPriority(1)
                .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/list')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"tools":[
                                   {"name":"get_alerts","description":"list firing alerts",
                                    "inputSchema":{"type":"object","properties":{},"required":[]},
                                    "outputSchema":{"type":"object","required":["count"]}}]}}""")));
        McpServerClient adapter = sdkAdapter();
        adapter.connect();

        List<McpServerClient.ToolDescriptor> tools = adapter.listTools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).outputSchema())
                .containsEntry("type", "object")
                .containsEntry("required", List.of("count"));
        adapter.close();
    }

    @Test
    @DisplayName("MCP-07/D02-6 接线核验：真实 SDK 收到 list_changed → McpMountManager.onToolsChanged 重检换快照")
    void toolsListChangedFromSdkTriggersManagerRevalidation() {
        // tools/list 场景化：首次（挂载校验）v1，其后（通知触发的重检）v2
        WIREMOCK.stubFor(post(urlEqualTo("/mcp")).atPriority(1)
                .inScenario("tools-changed")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/list')]"))
                .willSetStateTo("v2")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"tools":[
                                   {"name":"tool_v1","description":"v1",
                                    "inputSchema":{"type":"object","properties":{},"required":[]}}]}}""")));
        WIREMOCK.stubFor(post(urlEqualTo("/mcp")).atPriority(2)
                .inScenario("tools-changed")
                .whenScenarioStateIs("v2")
                .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/list')]"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody("""
                                {"jsonrpc":"2.0","id":"{{jsonPath request.body '$.id'}}",
                                 "result":{"tools":[
                                   {"name":"tool_v2","description":"v2",
                                    "inputSchema":{"type":"object","properties":{},"required":[]}}]}}""")));
        // 服务端→客户端 SSE 通知面：挂载完成后送达 list_changed（延迟避开挂载竞窗）
        WIREMOCK.stubFor(get(urlEqualTo("/mcp")).atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withHeader("Mcp-Session-Id", "spike-session-1")
                        .withFixedDelay(2_000)
                        .withBody("event: message\n"
                                + "data: {\"jsonrpc\":\"2.0\","
                                + "\"method\":\"notifications/tools/list_changed\"}\n\n")));

        var registry = new McpTestFixtures.MemRegistry();
        McpMountManager manager = new McpMountManager(registry,
                new SdkMcpServerClientFactory(Duration.ofSeconds(10), Duration.ofSeconds(2)),
                java.time.Clock.systemUTC(), Duration.ofMinutes(5), 2, java.util.Set.of());
        manager.register("wire", "streamable_http", WIREMOCK.baseUrl() + "/mcp", List.of(), null);
        assertThat(manager.snapshot().servers().get("wire").tools())
                .extracting(McpServerClient.ToolDescriptor::name)
                .as("挂载校验后 v1 schema 在快照").containsExactly("tool_v1");

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        manager.snapshot().servers().get("wire").tools())
                        .extracting(McpServerClient.ToolDescriptor::name)
                        .as("真实 SDK 通知 → onToolsChanged → 受控重校验后 v2 schema 生效")
                        .containsExactly("tool_v2"));
        manager.deregister("wire", Duration.ofMillis(200));
    }
}

package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.mcp.McpMountManager;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.FakeClient;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.FakeFactory;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.MemRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN-06 管理面 REST（§2.3 线 91：register/disable/enable/deregister + 状态）：
 * 直调方法脸（Web 层为 Spring 样板）；M11 拒绝在管理面即映射 403，不落库不建连接。
 */
class McpMountControllerTest {

    private final AtomicLong nowMs = new AtomicLong(1_000_000);

    private McpMountManager manager(FakeFactory factory) {
        MemRegistry repo = new MemRegistry();
        return new McpMountManager(repo, factory, McpTestFixtures.mutableClock(nowMs),
                Duration.ofMinutes(1), 2, java.util.Set.of("mcp-server-kit"));
    }

    @Test
    @DisplayName("注册→MOUNTED、状态面可见、disable→DISABLED 全链委托")
    void registerStatusDisableDelegateToManager() {
        FakeFactory factory = new FakeFactory();
        factory.suppliers.put("alert-kit",
                () -> new FakeClient(McpTestFixtures.tool("query_alerts")));
        McpMountController controller = new McpMountController(manager(factory));

        ResponseEntity<Map<String, Object>> created = controller.register(
                new McpMountController.RegisterRequest("alert-kit", "streamable_http",
                        "http://127.0.0.1:9999/mcp", List.of(), null));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).containsEntry("state", "MOUNTED")
                .containsEntry("name", "alert-kit");
        assertThat(controller.status()).containsKey("alert-kit");

        ResponseEntity<Map<String, Object>> disabled = controller.disable("alert-kit");
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.status().get("alert-kit").state())
                .isEqualTo(McpMountManager.ServerState.DISABLED);
    }

    @Test
    @DisplayName("M11：管理 API 请求任意 stdio 命令 → 403 拒绝（发布前，零连接零落库）")
    void arbitraryStdioCommandRejectedAsForbidden() {
        FakeFactory factory = new FakeFactory();
        McpMountController controller = new McpMountController(manager(factory));

        ResponseEntity<Map<String, Object>> rejected = controller.register(
                new McpMountController.RegisterRequest("evil", "stdio", "rm -rf /",
                        List.of(), null));

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).containsEntry("reason", "POLICY_DENIED");
        assertThat(factory.createCalls.get()).isZero();
        assertThat(controller.status()).isEmpty();
    }
}

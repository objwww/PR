package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Online Read Shadow 隔离单测（AM4 M4-35，TDD 先行）：影子工具面仅放行 R0/R1
 * （R2/R3 物理不进影子注册面，调用即 UNKNOWN_TOOL）；REDTEAM 命名空间物理禁入
 * （构造期 fail-fast + 调用期第二闸，双闸同 M4-16 惯例）；独立 slot（自有调用池）
 * 与独立限流（固定窗口配额，模型可见族 RATE_LIMITED，不动生产面）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class ShadowToolFaceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final long TIMEOUT_MILLIS = 4_000L;
    private static final long RESULT_LIMIT_BYTES = 65_536L;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final long SHADOW_QUOTA = 3L;
    private static final String SHADOW_BODY =
            "{\"status\":\"success\",\"data\":{\"result\":[\"shadow\"]}}";

    private final MutableClock clock = new MutableClock();

    @Test
    void shadowExecutesReadOnlyThroughOwnSlotAndQuota() {
        ExecutorService shadowPool = Executors.newFixedThreadPool(1);
        try {
            ShadowToolFace face = new ShadowToolFace(productionRegistry(),
                    new ToolPolicy(Set.of("prometheus.query")), shadowPool,
                    SHADOW_QUOTA, WINDOW_MILLIS, clock);

            // 正：R0 只读经影子面执行（独立 slot 内）
            ToolGateway.ToolInvocationResult ok = face.invoke(metricsInvocation());
            assertThat(ok.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.EXECUTED);
            assertThat(new String(ok.body(), StandardCharsets.UTF_8)).contains("shadow");

            // 反：R2 写面工具在生产注册面存在，但物理不进影子面 → UNKNOWN_TOOL
            assertThatThrownBy(() -> face.invoke(changeInvocation()))
                    .isInstanceOf(ToolControlPlaneException.class)
                    .hasMessageContaining("UNKNOWN_TOOL");

            // 独立限流：影子配额耗尽 → 模型可见族 RATE_LIMITED；生产面不受影响
            face.invoke(metricsInvocation());
            face.invoke(metricsInvocation());
            assertThatThrownBy(() -> face.invoke(metricsInvocation()))
                    .isInstanceOf(ToolModelVisibleException.class)
                    .hasMessageContaining("影子");
        } finally {
            shadowPool.shutdownNow();
        }
    }

    @Test
    void productionFaceUnaffectedWhenShadowQuotaExhausted() {
        ExecutorService shadowPool = Executors.newFixedThreadPool(1);
        ExecutorService productionPool = Executors.newFixedThreadPool(1);
        try {
            ToolRegistry registry = productionRegistry();
            ShadowToolFace face = new ShadowToolFace(registry,
                    new ToolPolicy(Set.of("prometheus.query")), shadowPool,
                    1L, WINDOW_MILLIS, clock);
            face.invoke(metricsInvocation());
            assertThatThrownBy(() -> face.invoke(metricsInvocation()))
                    .isInstanceOf(ToolModelVisibleException.class);

            // 生产面（无影子限流）照常执行——额度独立
            ToolGateway production = new ToolGateway(registry,
                    new ToolPolicy(Set.of("prometheus.query")), productionPool,
                    Clock.systemUTC(), null);
            assertThat(production.invoke(metricsInvocation()).kind())
                    .isEqualTo(ToolGateway.ToolInvocationResult.Kind.EXECUTED);
        } finally {
            shadowPool.shutdownNow();
            productionPool.shutdownNow();
        }
    }

    @Test
    void redteamIsPhysicallyBannedAtConstructionAndInvoke() {
        // 构造期：生产注册面混入 redteam 工具 → 影子面拒绝构建（fail-fast）
        ToolDefinition redteam = new ToolDefinition("redteam.attack", "1",
                windowSchema(), ToolRisk.R0, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolRegistry contaminated = new ToolRegistry(List.of(
                new ToolRegistry.Registration(MetricsAgent.toolDefinition(
                        TIMEOUT_MILLIS, RESULT_LIMIT_BYTES),
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(redteam,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8)))));
        ExecutorService shadowPool = Executors.newFixedThreadPool(1);
        try {
            assertThatThrownBy(() -> new ShadowToolFace(contaminated,
                    new ToolPolicy(Set.of("prometheus.query")), shadowPool,
                    SHADOW_QUOTA, WINDOW_MILLIS, clock))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REDTEAM");

            // 调用期第二闸：绕过构造检查的 redteam 调用被显式拒绝（不消耗配额）
            ShadowToolFace face = new ShadowToolFace(productionRegistry(),
                    new ToolPolicy(Set.of("prometheus.query")), shadowPool,
                    SHADOW_QUOTA, WINDOW_MILLIS, clock);
            assertThatThrownBy(() -> face.invoke(new ToolGateway.ToolInvocation(
                    RUN_ID, TASK_ID, ATTEMPT_ID, 1L, "redteam.attack", "1",
                    "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", Map.of(
                            "since", "0", "until", "1"), null)))
                    .isInstanceOf(ToolControlPlaneException.class)
                    .hasMessageContaining("物理禁入");
        } finally {
            shadowPool.shutdownNow();
        }
    }

    @Test
    void emptyShadowFaceIsStartupFailure() {
        // 生产注册面只有 R2/R3 → 影子面无可用工具 → 启动期硬失败
        ToolDefinition writeOnly = new ToolDefinition("change.query", "1",
                windowSchema(), ToolRisk.R2, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolRegistry writeOnlyRegistry = new ToolRegistry(List.of(
                new ToolRegistry.Registration(writeOnly,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8)))));
        ExecutorService shadowPool = Executors.newFixedThreadPool(1);
        try {
            assertThatThrownBy(() -> new ShadowToolFace(writeOnlyRegistry,
                    new ToolPolicy(Set.of("change.query")), shadowPool,
                    SHADOW_QUOTA, WINDOW_MILLIS, clock))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("影子");
        } finally {
            shadowPool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 夹具

    private ToolRegistry productionRegistry() {
        ToolDefinition prometheus = MetricsAgent.toolDefinition(
                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition changeWrite = new ToolDefinition("change.query", "1",
                windowSchema(), ToolRisk.R2, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(prometheus,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(changeWrite,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8)))));
    }

    private static Map<String, Object> windowSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("since", Map.of("type", "string"));
        properties.put("until", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("since", "until"));
        return schema;
    }

    private ToolGateway.ToolInvocation metricsInvocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, 1L,
                MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION,
                "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z", Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"), null);
    }

    private ToolGateway.ToolInvocation changeInvocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, 2L,
                "change.query", "1", "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z",
                Map.of("since", "0", "until", "1"), null);
    }

    /** 手动推进的测试时钟（限流窗口确定性） */
    static final class MutableClock extends Clock {

        private long millis = 1_790_000_000_000L;

        void advanceBy(long millisStep) {
            this.millis += millisStep;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}

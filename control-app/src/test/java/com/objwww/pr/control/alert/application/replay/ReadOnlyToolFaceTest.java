package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.DisplayName;
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
 * ReadOnlyToolFace REDTEAM 策略开关 UT（M6-01 C-70）：双闸从结构强制降为策略开关——
 * redteamOnly=true 保持影子面双闸（构造 fail-fast + 调用 POLICY_DENIED，原
 * ShadowToolFaceTest 不改一字回归锚）；redteamOnly=false（canary 期姿态）撤双闸，
 * 只依赖 R0/R1 裁剪物理兜底（R2/R3 物理不进注册面；R0/R1 的 redteam 工具是否放行
 * 属注册面编制纪律，不是本类管辖）。
 */
class ReadOnlyToolFaceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final long TIMEOUT_MILLIS = 4_000L;
    private static final long RESULT_LIMIT_BYTES = 65_536L;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final String SHADOW_BODY =
            "{\"status\":\"success\",\"data\":{\"result\":[\"shadow\"]}}";

    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("redteamOnly=false（canary 姿态）：构造期不拒 redteam 工具，R0 redteam 调用放行")
    void redteamOnlyFalseLiftsBothGates() {
        ToolRegistry contaminated = contaminatedRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            ReadOnlyToolFace face = new ReadOnlyToolFace(contaminated,
                    new ToolPolicy(Set.of("prometheus.query", "redteam.attack")), pool,
                    10L, WINDOW_MILLIS, clock, false);

            ToolGateway.ToolInvocationResult ok = face.invoke(redteamInvocation());
            assertThat(ok.kind()).isEqualTo(ToolGateway.ToolInvocationResult.Kind.EXECUTED);
            assertThat(new String(ok.body(), StandardCharsets.UTF_8)).contains("shadow");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("redteamOnly=false 仍有 R0/R1 物理兜底：R2 redteam 工具物理不进注册面 → UNKNOWN_TOOL")
    void redteamOnlyFalseKeepsRiskClipping() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            ReadOnlyToolFace face = new ReadOnlyToolFace(contaminatedRegistry(),
                    new ToolPolicy(Set.of("redteam.write")), pool,
                    10L, WINDOW_MILLIS, clock, false);

            assertThatThrownBy(() -> face.invoke(new ToolGateway.ToolInvocation(
                    RUN_ID, TASK_ID, ATTEMPT_ID, 1L, "redteam.write", "1",
                    WINDOW, Map.of("since", "0", "until", "1"), null)))
                    .isInstanceOf(ToolControlPlaneException.class)
                    .hasMessageContaining("UNKNOWN_TOOL");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("redteamOnly=true：双闸保持——构造期 fail-fast + 调用期 POLICY_DENIED")
    void redteamOnlyTrueKeepsBothGates() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            assertThatThrownBy(() -> new ReadOnlyToolFace(contaminatedRegistry(),
                    new ToolPolicy(Set.of("prometheus.query")), pool,
                    10L, WINDOW_MILLIS, clock, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REDTEAM");

            ReadOnlyToolFace face = new ReadOnlyToolFace(cleanRegistry(),
                    new ToolPolicy(Set.of("prometheus.query", "redteam.attack")), pool,
                    10L, WINDOW_MILLIS, clock, true);
            assertThatThrownBy(() -> face.invoke(redteamInvocation()))
                    .isInstanceOf(ToolControlPlaneException.class)
                    .hasMessageContaining("物理禁入");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static final String WINDOW = "2026-09-08T07:50:00Z/2026-09-08T08:00:00Z";

    /** redteam R0（可进只读裁剪面）+ redteam R2（裁剪必剔）+ 正常 R0 */
    private ToolRegistry contaminatedRegistry() {
        ToolDefinition redteamRead = new ToolDefinition("redteam.attack", "1",
                windowSchema(), ToolRisk.R0, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition redteamWrite = new ToolDefinition("redteam.write", "1",
                windowSchema(), ToolRisk.R2, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(redteamRead,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(redteamWrite,
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8))),
                new ToolRegistry.Registration(
                        com.objwww.pr.control.alert.application.agent.MetricsAgent.toolDefinition(
                                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES),
                        new ReplayToolExecutor(SHADOW_BODY.getBytes(StandardCharsets.UTF_8)))));
    }

    /** 无 redteam 污染的正常注册面 */
    private ToolRegistry cleanRegistry() {
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        com.objwww.pr.control.alert.application.agent.MetricsAgent.toolDefinition(
                                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES),
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

    private ToolGateway.ToolInvocation redteamInvocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, 1L,
                "redteam.attack", "1", WINDOW,
                Map.of("since", "0", "until", "1"), null);
    }

    /** 手动推进的测试时钟（限流窗口确定性，ShadowToolFaceTest 同构） */
    static final class MutableClock extends Clock {

        private long millis = 1_790_000_000_000L;

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

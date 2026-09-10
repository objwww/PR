package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REPLAY_MOCK 精确匹配单测（AM4 M4-32，TDD 先行）：action envelope 六字段
 * （tool/version/args/time/snapshot/schema）全相同才回放（六字段经 ActionDigest
 * canonical 化为单一回放键），任一字段不同 = REPLAY_MISS 显式结局；回放模式
 * 结构上无执行器调用路径（MISS 绝不降级为活执行）；同 digest 异响应 = 记录
 * 冲突显式拒绝（禁静默覆盖）；与活执行网关对同一调用的 action digest 全等
 * （M4-33 两侧互认的锚点）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class ReplayToolGatewayTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final long CALL_SEQ = 1L;
    private static final long TIMEOUT_MILLIS = 4_000L;
    private static final long RESULT_LIMIT_BYTES = 65_536L;

    private static final String TOOL_PROM = MetricsAgent.TOOL_NAME;
    private static final String TOOL_PROM_V2 = "2";
    private static final String TOOL_LOGS = LogsAgent.TOOL_NAME;
    private static final String TOOL_UNKNOWN = "change.execute";

    private static final String TIME_RANGE = "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";
    private static final String TIME_RANGE_ALT = "2026-09-05T08:00:00Z/2026-09-05T09:00:00Z";
    private static final com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
            SNAPSHOT = new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                    "ab".repeat(32));
    private static final com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
            SNAPSHOT_ALT = new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                    "cd".repeat(32));

    private static final byte[] RECORDED_RESPONSE =
            "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONFLICT_RESPONSE =
            "{\"status\":\"success\"} ".getBytes(StandardCharsets.UTF_8);
    /** 注册执行器只会返回的"活执行"字节：若回放误降级活执行，body 便不是 null/录制值 */
    private static final byte[] LIVE_ONLY_RESPONSE =
            "{\"status\":\"live-only\"}".getBytes(StandardCharsets.UTF_8);

    private final MemReplayStore store = new MemReplayStore();

    @Test
    void identicalInvocationReplays() {
        ReplayToolGateway gateway = replayGateway();
        gateway.record(metricsInvocation(), RECORDED_RESPONSE);

        ReplayToolGateway.ReplayResult result = gateway.invoke(metricsInvocation());

        assertThat(result.kind()).isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_HIT);
        assertThat(result.body()).isEqualTo(RECORDED_RESPONSE);
        assertThat(result.actionDigest()).isNotBlank();
    }

    @Test
    void everyEnvelopeFieldDifferenceIsReplayMiss() {
        ReplayToolGateway gateway = replayGateway();
        gateway.record(metricsInvocation(), RECORDED_RESPONSE);

        // 扰动全部落在已注册且 schema 合法的空间内：tool/version/args 值/
        // time/snapshot(含 null)——args 多声明外字段在 schema 校验即 INVALID_ARGS
        // （到不了 digest 阶段，由 replayModeStillEnforcesArgsSchema 单独锚定）
        assertThat(gateway.invoke(logsInvocation()).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(gateway.invoke(prometheusV2Invocation()).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(gateway.invoke(withQuery("cpu")).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(gateway.invoke(withTimeRange(TIME_RANGE_ALT)).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(gateway.invoke(withSnapshot(SNAPSHOT_ALT)).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(gateway.invoke(withSnapshot(null)).kind())
                .isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
    }

    @Test
    void missNeverFallsBackToLiveExecution() {
        ReplayToolGateway gateway = replayGateway();
        // 回放账本为空：注册执行器在册但结构性不可达——MISS 是结局不是降级
        ReplayToolGateway.ReplayResult result = gateway.invoke(metricsInvocation());

        assertThat(result.kind()).isEqualTo(ReplayToolGateway.ReplayResult.ReplayKind.REPLAY_MISS);
        assertThat(result.body()).isNull();
        assertThat(result.actionDigest()).isNotBlank();
    }

    @Test
    void sameDigestDifferentResponseIsRecordConflict() {
        ReplayToolGateway gateway = replayGateway();
        gateway.record(metricsInvocation(), RECORDED_RESPONSE);

        assertThatThrownBy(() -> gateway.record(metricsInvocation(), CONFLICT_RESPONSE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突");
        // 原记录不被覆盖
        assertThat(gateway.invoke(metricsInvocation()).body()).isEqualTo(RECORDED_RESPONSE);
    }

    @Test
    void sameDigestSameResponseIsIdempotent() {
        ReplayToolGateway gateway = replayGateway();
        gateway.record(metricsInvocation(), RECORDED_RESPONSE);
        gateway.record(metricsInvocation(), RECORDED_RESPONSE);

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void replayModeStillEnforcesArgsSchema() {
        ReplayToolGateway gateway = replayGateway();

        // 未声明字段硬拒绝——回放模式不是 schema 纪律的旁路
        Map<String, Object> invalidArgs = Map.of("query", "up", "bogus", "1");
        assertThatThrownBy(() -> gateway.invoke(withArgs(invalidArgs)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("INVALID_ARGS");
    }

    @Test
    void unknownToolIsRejectedLikeLiveGateway() {
        ReplayToolGateway gateway = replayGateway();

        assertThatThrownBy(() -> gateway.invoke(withTool(TOOL_UNKNOWN)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("UNKNOWN_TOOL");
    }

    @Test
    void digestMatchesLiveGatewayForSameInvocation() {
        ExecutorService callPool = Executors.newSingleThreadExecutor();
        try {
            ToolGateway live = new ToolGateway(registry(),
                    new ToolPolicy(Set.of(TOOL_PROM)), callPool, Clock.systemUTC(), null);
            String liveDigest = live.invoke(metricsInvocation()).actionDigest();

            ReplayToolGateway gateway = replayGateway();
            gateway.record(metricsInvocation(), RECORDED_RESPONSE);

            // 两侧对同一调用计算同一 digest——回放匹配与活执行审计互认
            assertThat(gateway.invoke(metricsInvocation()).actionDigest())
                    .isEqualTo(liveDigest);
        } finally {
            callPool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 夹具

    private ReplayToolGateway replayGateway() {
        return new ReplayToolGateway(registry(), store);
    }

    /**
     * 注册面：prometheus.query@1（真实 schema）+ 同 schema 的 @2（版本扰动面）
     * + logs.query@1（工具扰动面）。执行器返回 LIVE_ONLY 字节且结构性不可达。
     */
    private ToolRegistry registry() {
        ToolDefinition promV1 = MetricsAgent.toolDefinition(
                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition promV2 = new ToolDefinition(TOOL_PROM, TOOL_PROM_V2,
                promV1.schema(), ToolRisk.R0, TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition logsV1 = LogsAgent.toolDefinition(
                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(promV1, new ReplayToolExecutor(LIVE_ONLY_RESPONSE)),
                new ToolRegistry.Registration(promV2, new ReplayToolExecutor(LIVE_ONLY_RESPONSE)),
                new ToolRegistry.Registration(logsV1, new ReplayToolExecutor(LIVE_ONLY_RESPONSE))));
    }

    private ToolGateway.ToolInvocation metricsInvocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, MetricsAgent.TOOL_VERSION, TIME_RANGE, Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation logsInvocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_LOGS, LogsAgent.TOOL_VERSION, TIME_RANGE, Map.of(
                        "since", "0", "until", "1"),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation prometheusV2Invocation() {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, TOOL_PROM_V2, TIME_RANGE, Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation withQuery(String expr) {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, MetricsAgent.TOOL_VERSION, TIME_RANGE, Map.of(
                        "query", expr, "start", "0", "end", "1", "step", "30s"),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation withArgs(Map<String, Object> args) {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, MetricsAgent.TOOL_VERSION, TIME_RANGE, args, SNAPSHOT);
    }

    private ToolGateway.ToolInvocation withTimeRange(String timeRange) {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, MetricsAgent.TOOL_VERSION, timeRange, Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation withSnapshot(
            com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest snapshot) {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                TOOL_PROM, MetricsAgent.TOOL_VERSION, TIME_RANGE, Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"),
                snapshot);
    }

    private ToolGateway.ToolInvocation withTool(String toolName) {
        return new ToolGateway.ToolInvocation(RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ,
                toolName, MetricsAgent.TOOL_VERSION, TIME_RANGE, Map.of(
                        "query", "up", "start", "0", "end", "1", "step", "30s"),
                SNAPSHOT);
    }

    /** 回放账本内存件（哑存储：冲突纪律由 ReplayToolGateway.record 强制） */
    static final class MemReplayStore implements ToolReplayStore {

        private final Map<String, ReplayRecord> records = new LinkedHashMap<>();

        @Override
        public void put(ReplayRecord record) {
            records.put(record.actionDigest(), record);
        }

        @Override
        public Optional<ReplayRecord> find(String actionDigest) {
            return Optional.ofNullable(records.get(actionDigest));
        }

        int size() {
            return records.size();
        }
    }
}

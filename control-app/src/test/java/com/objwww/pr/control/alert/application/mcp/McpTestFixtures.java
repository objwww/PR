package com.objwww.pr.control.alert.application.mcp;

import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * EN-06 机制面测试夹具（M01～12 E 脸共用）：内存注册表/假 server 客户端/假客户端
 * 工厂 + 可拨时钟 + 内存账本。假工厂对未知 server 抛 {@link AssertionError}——
 * 任何"未预期的客户端创建"（如 M10 高权限回退、M12 复活 disabled）直接炸红测试。
 * public（仅测试树）：供 interfaces 包（McpMountControllerTest）复用。
 */
public final class McpTestFixtures {

    private McpTestFixtures() {
    }

    // ---------------------------------------------------------------- 内存注册表

    /** generation 全局单调递增（V64 语义：generation 单调，同名 upsert 顶替不新增行）。 */
    public static final class MemRegistry implements McpServerRegistryRepository {

        final Map<String, ServerRecord> rows = new ConcurrentHashMap<>();
        private final AtomicLong generation = new AtomicLong();

        void seed(String name, boolean enabled, long gen) {
            rows.put(name, new ServerRecord(httpSpec(name, enabled), gen, Instant.ofEpochSecond(0)));
            generation.accumulateAndGet(gen, Math::max);
        }

        @Override
        public List<ServerRecord> loadAll() {
            return rows.values().stream()
                    .sorted(Comparator.comparingLong(ServerRecord::generation))
                    .toList();
        }

        @Override
        public ServerRecord upsert(ServerSpec spec) {
            ServerRecord record = new ServerRecord(spec, generation.incrementAndGet(), Instant.now());
            rows.put(spec.name(), record);
            return record;
        }

        @Override
        public boolean delete(String name) {
            return rows.remove(name) != null;
        }
    }

    public static McpServerRegistryRepository.ServerSpec httpSpec(String name, boolean enabled) {
        return new McpServerRegistryRepository.ServerSpec(
                name, "streamable_http", "http://127.0.0.1:9/mcp", List.of(), null, enabled);
    }

    // ---------------------------------------------------------------- 假客户端/工厂

    public static final class FakeClient implements McpServerClient {

        final AtomicInteger listToolsCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        volatile List<ToolDescriptor> currentTools;
        volatile RuntimeException connectError;
        volatile RuntimeException listToolsError;
        final Queue<Object> callResults = new ConcurrentLinkedQueue<>();
        final List<String> receivedTools = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> receivedArgs = new CopyOnWriteArrayList<>();

        public FakeClient(ToolDescriptor... tools) {
            this.currentTools = List.of(tools);
        }

        @Override
        public void connect() {
            if (connectError != null) {
                throw connectError;
            }
        }

        @Override
        public List<ToolDescriptor> listTools() {
            listToolsCalls.incrementAndGet();
            if (listToolsError != null) {
                throw listToolsError;
            }
            return currentTools;
        }

        @Override
        public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
            receivedTools.add(toolName);
            receivedArgs.add(Map.copyOf(arguments));
            Object next = callResults.poll();
            if (next instanceof RuntimeException error) {
                throw error;
            }
            return next instanceof McpToolResult result ? result : McpToolResult.text("ok:" + toolName);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    public static final class FakeFactory implements McpMountManager.McpClientFactory {

        /** public：跨包消费面（McpMountControllerTest）装配假客户端 */
        public final Map<String, Supplier<FakeClient>> suppliers = new ConcurrentHashMap<>();
        final List<FakeClient> created = new CopyOnWriteArrayList<>();
        /** public：跨包消费面（McpMountControllerTest）钉"零连接尝试" */
        public final AtomicInteger createCalls = new AtomicInteger();

        @Override
        public McpServerClient create(McpServerRegistryRepository.ServerSpec spec) {
            createCalls.incrementAndGet();
            Supplier<FakeClient> supplier = suppliers.get(spec.name());
            if (supplier == null) {
                throw new AssertionError("unexpected client create: " + spec.name());
            }
            FakeClient client = supplier.get();
            created.add(client);
            return client;
        }
    }

    public static McpServerClient.ToolDescriptor tool(String name) {
        return new McpServerClient.ToolDescriptor(name, "desc of " + name, Map.of("type", "object"));
    }

    // ---------------------------------------------------------------- 可拨时钟

    /** TTL/TTL 重校验面用：测试内 nowMs.addAndGet 拨针。 */
    public static Clock mutableClock(AtomicLong nowMs) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(nowMs.get());
            }
        };
    }

    // ---------------------------------------------------------------- 内存账本

    public static final class MemLedger implements RcaToolInvocationLedger {

        public record Row(UUID operationId, UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String actionDigest,
                ToolInvocationState state, ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.runId(), identity.taskId(),
                    identity.attemptId(), identity.callSeq(), identity.toolName(),
                    identity.toolVersion(), identity.actionDigest(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal, ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(), row.toolVersion(),
                            row.actionDigest(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }
}

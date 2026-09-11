package com.objwww.pr.control.alert.application.mcp;

import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository.ServerRecord;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository.ServerSpec;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 动态挂载管理面（EN-06，§2.3 三范式组合）：
 * <ul>
 *   <li><b>注册快照</b>：{@link AtomicReference} 不可变快照无锁读；网络校验与候选构建
 *       在发布锁外，短临界区内复核期望 revision 后原子换针，竞败者关闭未使用候选；
 *       普通失败不替换健康快照（M02）。</li>
 *   <li><b>会话策略</b>：短会话有界 TTL 重校验（M05）；list_changed 只触发候选重检
 *       （M04），旧快照引用不可变、旧 Run 不被静默换参。</li>
 *   <li><b>下线范式 C</b>：disable 读侧立即生效（独立生效，不等连通性，M06）；等在飞
 *       drain 或 idle 超时；delete+关连接（引用释放后恰好关闭一次，M07）。</li>
 *   <li><b>M11</b>：stdio 仅运维预装固定命令（允许清单），http(s) 校验——发布前拒绝，
 *       零进程零连接零落库。</li>
 *   <li><b>M12</b>：从 PG 恢复一致 revision（=max generation）；disabled 不复活
 *       （dormant 无连接）；重复注册幂等顶替。</li>
 * </ul>
 * generation 与 driver leaseEpoch 分开保存（不共用，§2.3 竞态语义）。
 */
public class McpMountManager {

    private static final Logger log = LoggerFactory.getLogger(McpMountManager.class);

    public enum ServerState { MOUNTED, DISABLED, FAILED }

    public record MountStatus(String serverName, ServerState state, String reason,
            long generation, List<McpServerClient.ToolDescriptor> tools) {
    }

    /** 挂载条目：持久行 + 已校验连接 + 工具清单 + 校验时刻（TTL 基准） */
    public record MountedServer(ServerRecord record, McpServerClient client,
            List<McpServerClient.ToolDescriptor> tools, long validatedAtMs) {
    }

    /** 不可变注册快照：revision = max(revision, 条目 generation)（M04 重校验另 +1） */
    public record RegistrySnapshot(long revision, Map<String, MountedServer> servers) {

        public RegistrySnapshot {
            servers = Map.copyOf(servers);
        }

        RegistrySnapshot with(MountedServer mounted) {
            Map<String, MountedServer> next = new LinkedHashMap<>(servers);
            next.put(mounted.record().spec().name(), mounted);
            return new RegistrySnapshot(Math.max(revision, mounted.record().generation()), next);
        }

        RegistrySnapshot without(String name) {
            Map<String, MountedServer> next = new LinkedHashMap<>(servers);
            next.remove(name);
            return new RegistrySnapshot(revision, next);
        }

        /** 候选重检通过后的原子换针（schema 更新：revision 前进，旧引用不动） */
        RegistrySnapshot withTools(String name, List<McpServerClient.ToolDescriptor> tools,
                long validatedAtMs) {
            MountedServer current = servers.get(name);
            MountedServer next = new MountedServer(current.record(), current.client(),
                    List.copyOf(tools), validatedAtMs);
            Map<String, MountedServer> updated = new LinkedHashMap<>(servers);
            updated.put(name, next);
            return new RegistrySnapshot(revision + 1, updated);
        }
    }

    /** 连接工厂（真实现包 SDK client；机制测试用假件） */
    public interface McpClientFactory {
        McpServerClient create(ServerSpec spec);
    }

    private final McpServerRegistryRepository repository;
    private final McpClientFactory clientFactory;
    private final Clock clock;
    private final Duration schemaTtl;
    private final int maxRevalidationAttempts;
    private final Set<String> allowedStdioCommands;

    private final AtomicReference<RegistrySnapshot> snapshotRef =
            new AtomicReference<>(new RegistrySnapshot(0, Map.of()));
    /** 发布锁：短临界区（换针/资格领取/禁用提交）——网络操作一律锁外 */
    private final Object publishLock = new Object();
    private final Map<String, MountStatus> failedStatuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    /** 关闭恰好一次（drain/deregister/替换多路径共用） */
    private final Set<McpServerClient> closedClients = ConcurrentHashMap.newKeySet();

    public McpMountManager(McpServerRegistryRepository repository, McpClientFactory clientFactory,
            Clock clock, Duration schemaTtl, int maxRevalidationAttempts,
            Set<String> allowedStdioCommands) {
        this.repository = repository;
        this.clientFactory = clientFactory;
        this.clock = clock;
        this.schemaTtl = schemaTtl;
        this.maxRevalidationAttempts = maxRevalidationAttempts;
        this.allowedStdioCommands = Set.copyOf(allowedStdioCommands);
    }

    // ------------------------------------------------------------------ 发布面

    /** 注册/重注册：M11 校验 → 锁外候选构建 → 短临界区复核换针（M12 幂等顶替） */
    public MountStatus register(String name, String transport, String endpoint,
            List<String> args, String headersRef) {
        ServerSpec spec = new ServerSpec(name, transport, endpoint,
                args == null ? List.of() : List.copyOf(args), headersRef, true);
        validateSpec(spec);
        failedStatuses.remove(name);
        return mountCandidate(spec);
    }

    /** 重新启用已禁用 server：与注册同管线（新候选 + 短临界区换针） */
    public MountStatus enable(String name) {
        MountedServer entry = snapshotRef.get().servers().get(name);
        if (entry == null) {
            throw unknownTool(name);
        }
        ServerSpec spec = withEnabled(entry.record().spec(), true);
        failedStatuses.remove(name);
        return mountCandidate(spec);
    }

    /** 紧急禁用：读侧立即不可见不可调（独立生效，不等连通性验证，M06）；在飞不打断 */
    public MountStatus disable(String name) {
        synchronized (publishLock) {
            RegistrySnapshot current = snapshotRef.get();
            MountedServer entry = current.servers().get(name);
            if (entry == null) {
                throw unknownTool(name);
            }
            ServerRecord record = repository.upsert(withEnabled(entry.record().spec(), false));
            snapshotRef.set(current.with(new MountedServer(record, entry.client(),
                    entry.tools(), entry.validatedAtMs())));
            return new MountStatus(name, ServerState.DISABLED, null, record.generation(), List.of());
        }
    }

    /** 范式 C 第 1+2 步：disable 提交后等在飞 drain 或 idle 超时（不永久等待，M07） */
    public void disableAndWaitDrain(String name, Duration drainTimeout) {
        disable(name);
        drain(name, drainTimeout);
    }

    /** 范式 C 第 3 步：读侧先摘除 → drain → 落库删除 → 关连接（恰好一次） */
    public void deregister(String name, Duration drainTimeout) {
        McpServerClient client = null;
        synchronized (publishLock) {
            RegistrySnapshot current = snapshotRef.get();
            MountedServer entry = current.servers().get(name);
            if (entry != null) {
                client = entry.client();
                snapshotRef.set(current.without(name));
            }
            failedStatuses.remove(name);
        }
        awaitDrain(name, drainTimeout);
        repository.delete(name);
        closeQuietly(client);
    }

    /** M12：进程重启后从 PG 恢复——enabled 重校验挂载、disabled 保持 dormant 不复活 */
    public RegistrySnapshot recover() {
        List<ServerRecord> rows = repository.loadAll();
        long revision = 0;
        Map<String, MountedServer> mounted = new LinkedHashMap<>();
        Map<String, MountStatus> failures = new LinkedHashMap<>();
        for (ServerRecord row : rows) {
            revision = Math.max(revision, row.generation());
            if (!row.spec().enabled()) {
                mounted.put(row.spec().name(), new MountedServer(row, null, List.of(), 0L));
                continue;
            }
            McpServerClient candidate = null;
            try {
                candidate = clientFactory.create(row.spec());
                candidate.connect();
                mounted.put(row.spec().name(), new MountedServer(row, candidate,
                        List.copyOf(candidate.listTools()), nowMs()));
            } catch (Exception e) {
                closeQuietly(candidate);
                failures.put(row.spec().name(), failedStatus(row, e));
                log.warn("MCP server {} 恢复失败（{}），不挂载待人工重试",
                        row.spec().name(), sanitized(e));
            }
        }
        synchronized (publishLock) {
            long expected = snapshotRef.get().revision();
            snapshotRef.set(new RegistrySnapshot(Math.max(expected, revision), mounted));
            failedStatuses.putAll(failures);
            return snapshotRef.get();
        }
    }

    // ------------------------------------------------------------------ 会话策略

    /** M04：list_changed 通知只触发候选重检——不自动授权新增工具，不静默换快照 */
    public void onToolsChanged(String serverName) {
        MountedServer entry = snapshotRef.get().servers().get(serverName);
        if (entry == null || !entry.record().spec().enabled()) {
            return;
        }
        revalidate(serverName);
    }

    /**
     * 派发前资格与新鲜度门：未挂载 → UNKNOWN_TOOL；已禁用 → CAPABILITY_REVOKED（M06）；
     * TTL 到期先重校验（M05，旧 schema 不误用）。
     */
    public MountedServer ensureFresh(String name) {
        MountedServer entry = snapshotRef.get().servers().get(name);
        if (entry == null) {
            throw unknownTool(name);
        }
        if (!entry.record().spec().enabled()) {
            throw new ToolControlPlaneException(ToolControlReason.CAPABILITY_REVOKED,
                    "CAPABILITY_REVOKED: MCP server 已禁用: " + name);
        }
        if (nowMs() - entry.validatedAtMs() > schemaTtl.toMillis()) {
            return revalidate(name);
        }
        return entry;
    }

    /** 有界重校验（短会话同一连接）：普通失败不替换健康快照；耗尽按因 AUTH_FAILED/QUERY_FAILED 终止（M10） */
    public MountedServer revalidate(String name) {
        MountedServer entry = snapshotRef.get().servers().get(name);
        if (entry == null) {
            throw unknownTool(name);
        }
        Exception last = null;
        for (int attempt = 0; attempt < maxRevalidationAttempts; attempt++) {
            try {
                List<McpServerClient.ToolDescriptor> tools =
                        List.copyOf(entry.client().listTools());
                synchronized (publishLock) {
                    snapshotRef.set(snapshotRef.get().withTools(name, tools, nowMs()));
                }
                return snapshotRef.get().servers().get(name);
            } catch (Exception e) {
                last = e;
            }
        }
        failedStatuses.put(name, new MountStatus(name, ServerState.FAILED, sanitized(last),
                entry.record().generation(), List.of()));
        throw classified(last, name);
    }

    // ------------------------------------------------------------------ 资格领取

    /** 发送资格领取：与 disable 在发布锁内串行化（§2.3——禁用先提交则无新资格，M06） */
    public void enterInvocation(String name) {
        synchronized (publishLock) {
            MountedServer entry = snapshotRef.get().servers().get(name);
            if (entry == null) {
                throw unknownTool(name);
            }
            if (!entry.record().spec().enabled()) {
                throw new ToolControlPlaneException(ToolControlReason.CAPABILITY_REVOKED,
                        "CAPABILITY_REVOKED: MCP server 已禁用: " + name);
            }
            inFlight.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    public void exitInvocation(String name) {
        AtomicInteger count = inFlight.get(name);
        if (count != null) {
            count.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    // ------------------------------------------------------------------ 查询面

    public RegistrySnapshot snapshot() {
        return snapshotRef.get();
    }

    /** M02：健康快照条目按 enabled 推导状态；未挂载但注册失败的显示失败原因 */
    public Optional<MountStatus> status(String name) {
        MountedServer entry = snapshotRef.get().servers().get(name);
        if (entry != null) {
            ServerState state = entry.record().spec().enabled()
                    ? ServerState.MOUNTED : ServerState.DISABLED;
            return Optional.of(new MountStatus(name, state, null,
                    entry.record().generation(), entry.tools()));
        }
        return Optional.ofNullable(failedStatuses.get(name));
    }

    // ------------------------------------------------------------------ 内部

    private MountStatus mountCandidate(ServerSpec spec) {
        for (int round = 0; round < 2; round++) {
            long expected = snapshotRef.get().revision();
            McpServerClient candidate = null;
            try {
                candidate = clientFactory.create(spec);
                candidate.connect();
                List<McpServerClient.ToolDescriptor> tools =
                        List.copyOf(candidate.listTools());
                ServerRecord record = repository.upsert(spec);
                synchronized (publishLock) {
                    RegistrySnapshot current = snapshotRef.get();
                    if (current.revision() != expected) {
                        closeQuietly(candidate);
                        continue;
                    }
                    MountedServer previous = current.servers().get(spec.name());
                    snapshotRef.set(current.with(new MountedServer(record, candidate,
                            tools, nowMs())));
                    closeQuietly(previous == null ? null : previous.client());
                    return status(spec.name()).orElseThrow();
                }
            } catch (Exception e) {
                closeQuietly(candidate);
                MountStatus failure = new MountStatus(spec.name(), ServerState.FAILED,
                        sanitized(e), 0L, List.of());
                failedStatuses.put(spec.name(), failure);
                return failure;
            }
        }
        throw new IllegalStateException("MCP 注册并发冲突超限，请重试: " + spec.name());
    }

    private void drain(String name, Duration drainTimeout) {
        MountedServer entry = snapshotRef.get().servers().get(name);
        if (entry == null) {
            return;
        }
        awaitDrain(name, drainTimeout);
        closeQuietly(entry.client());
    }

    private void awaitDrain(String name, Duration drainTimeout) {
        AtomicInteger count = inFlight.get(name);
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (count != null && count.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** M11：stdio 仅运维预装固定命令（允许清单外拒绝）；http 仅 http(s)；未知 transport 拒绝 */
    private void validateSpec(ServerSpec spec) {
        switch (spec.transport() == null ? "" : spec.transport()) {
            case "streamable_http" -> {
                String endpoint = spec.endpoint() == null ? "" : spec.endpoint().toLowerCase();
                if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                    throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                            "M11: 仅接受 http(s) streamable_http 端点，非 http(s) URL 发布前拒绝");
                }
            }
            case "stdio" -> {
                if (!allowedStdioCommands.contains(spec.endpoint())) {
                    throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                            "M11: stdio 仅允许运维预装的固定命令，清单外命令发布前拒绝");
                }
            }
            default -> throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                    "M11: 未知 transport，仅支持 streamable_http/stdio");
        }
    }

    private ServerSpec withEnabled(ServerSpec spec, boolean enabled) {
        return new ServerSpec(spec.name(), spec.transport(), spec.endpoint(),
                spec.args(), spec.headersRef(), enabled);
    }

    private MountStatus failedStatus(ServerRecord row, Exception cause) {
        return new MountStatus(row.spec().name(), ServerState.FAILED, sanitized(cause),
                row.generation(), List.of());
    }

    private ToolControlPlaneException classified(Exception cause, String name) {
        if (cause instanceof McpServerClient.AuthException) {
            return new ToolControlPlaneException(ToolControlReason.AUTH_FAILED,
                    "AUTH_FAILED: MCP server " + name + " 重校验失败（鉴权被拒，"
                            + maxRevalidationAttempts + " 次有界重试后放弃，无回退）");
        }
        return new ToolControlPlaneException(ToolControlReason.QUERY_FAILED,
                "QUERY_FAILED: MCP server " + name + " 重校验失败（"
                        + (cause == null ? "unknown" : cause.getClass().getSimpleName())
                        + "，" + maxRevalidationAttempts + " 次有界重试后放弃）");
    }

    private static ToolControlPlaneException unknownTool(String name) {
        return new ToolControlPlaneException(ToolControlReason.UNKNOWN_TOOL,
                "UNKNOWN_TOOL: MCP server 未挂载: " + name);
    }

    /** 失败原因脱敏：只落异常类名，不含消息/凭据/端点（M10 无日志泄密） */
    private static String sanitized(Exception cause) {
        return cause == null ? "unknown" : cause.getClass().getSimpleName();
    }

    private void closeQuietly(McpServerClient client) {
        if (client == null || !closedClients.add(client)) {
            return;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.debug("MCP client 关闭异常（忽略）: {}", e.getClass().getSimpleName());
        }
    }

    private long nowMs() {
        return clock.instant().toEpochMilli();
    }
}

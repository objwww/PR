package com.objwww.pr.control.alert.application.mcp;

import com.objwww.pr.control.alert.application.mcp.McpMountManager.MountStatus;
import com.objwww.pr.control.alert.application.mcp.McpMountManager.RegistrySnapshot;
import com.objwww.pr.control.alert.application.mcp.McpMountManager.ServerState;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.FakeClient;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.FakeFactory;
import com.objwww.pr.control.alert.application.mcp.McpTestFixtures.MemRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EN-06 MCP 单点——挂载管理面机制（M01～M12 E 脸，§2.3 三范式组合）：
 * 注册快照（AtomicReference 无锁读 + 发布锁短临界区换针 + 竞败者关闭候选）、
 * 会话策略（TTL 重校验/list_changed 只触发候选重检）、下线范式 C（disable→drain→关连接）、
 * M11 发布前拒绝（stdio 允许清单 + http(s) 校验）、M12 PG 恢复幂等。
 * 网络真面由 En06McpSdkSpikeTest（真 SDK × WireMock）与 En06McpRegistryIT（真 PG，195 窗）补。
 */
class McpMountManagerTest {

    private static final String HTTP = "streamable_http";
    private static final long TTL_MS = 60_000;

    private final AtomicLong nowMs = new AtomicLong(1_000_000);

    private McpMountManager manager(MemRegistry repo, FakeFactory factory, long ttlMs) {
        return new McpMountManager(repo, factory, McpTestFixtures.mutableClock(nowMs),
                Duration.ofMillis(ttlMs), 2, Set.of("mcp-server-kit"));
    }

    private McpMountManager mounted(MemRegistry repo, FakeFactory factory, FakeClient client,
            String name, long ttlMs) {
        factory.suppliers.put(name, () -> client);
        McpMountManager manager = manager(repo, factory, ttlMs);
        manager.register(name, HTTP, "http://127.0.0.1:9999/mcp", List.of(), null);
        return manager;
    }

    @Test
    @DisplayName("M01(挂载面)：注册受控只读 server——握手/schema 校验后 MOUNTED，工具可见")
    void registerMountsAfterHandshakeAndSchemaValidation() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("query_alerts"));

        McpMountManager manager = mounted(repo, factory, client, "alert-kit", TTL_MS);

        MountStatus status = manager.status("alert-kit").orElseThrow();
        assertThat(status.state()).isEqualTo(ServerState.MOUNTED);
        assertThat(status.generation()).isEqualTo(1L);
        assertThat(status.tools()).extracting(McpServerClient.ToolDescriptor::name)
                .containsExactly("query_alerts");
        assertThat(manager.snapshot().revision()).isEqualTo(1L);
        assertThat(client.closeCalls.get()).as("健康挂载不关连接").isZero();
    }

    @Test
    @DisplayName("M02：握手失败的新注册不替换健康快照，候选连接释放，管理面显示原因")
    void failedRegistrationKeepsHealthySnapshotAndClosesCandidate() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient healthy = new FakeClient(McpTestFixtures.tool("t1"));
        FakeClient bad = new FakeClient();
        bad.connectError = new McpServerClient.AuthException("HTTP 401 session rejected");
        factory.suppliers.put("healthy", () -> healthy);
        factory.suppliers.put("bad", () -> bad);
        McpMountManager manager = manager(repo, factory, TTL_MS);
        manager.register("healthy", HTTP, "http://h/mcp", List.of(), null);
        RegistrySnapshot before = manager.snapshot();

        MountStatus status = manager.register("bad", HTTP, "http://bad/mcp", List.of(), null);

        assertThat(status.state()).isEqualTo(ServerState.FAILED);
        assertThat(status.reason()).as("管理面显示原因（脱敏）").isNotBlank();
        assertThat(bad.closeCalls.get()).as("候选连接释放").isEqualTo(1);
        assertThat(manager.snapshot()).as("健康快照不替换").isSameAs(before);
        assertThat(manager.snapshot().servers()).containsOnlyKeys("healthy");
        assertThat(repo.rows).as("未校验通过的配置不落库").containsOnlyKeys("healthy");
        manager.enterInvocation("healthy");
        manager.exitInvocation("healthy");
    }

    @Test
    @DisplayName("M04：list_changed 只触发候选重检——新 schema 经校验原子换快照，旧快照引用不静默换参")
    void toolsListChangedTriggersCandidateRevalidationNotSilentSwap() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", TTL_MS);
        RegistrySnapshot before = manager.snapshot();
        client.currentTools = List.of(McpTestFixtures.tool("t1"), McpTestFixtures.tool("t2"));

        manager.onToolsChanged("alert-kit");

        RegistrySnapshot after = manager.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.servers().get("alert-kit").tools())
                .extracting(McpServerClient.ToolDescriptor::name)
                .containsExactly("t1", "t2");
        assertThat(before.servers().get("alert-kit").tools())
                .as("旧快照不可变：旧 Run 持有的引用不被静默换参")
                .extracting(McpServerClient.ToolDescriptor::name)
                .containsExactly("t1");
    }

    @Test
    @DisplayName("M05(TTL 面)：短会话期上游变更不承诺通知——TTL 到期派发前重校验，新 schema 生效")
    void ttlExpiryRevalidatesBeforeUse() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", 100);
        client.currentTools = List.of(McpTestFixtures.tool("t9"));
        nowMs.addAndGet(200);

        McpMountManager.MountedServer entry = manager.ensureFresh("alert-kit");

        assertThat(client.listToolsCalls.get()).as("TTL 到期触发重校验").isEqualTo(2);
        assertThat(entry.tools()).extracting(McpServerClient.ToolDescriptor::name)
                .containsExactly("t9");
        McpMountManager.MountedServer again = manager.ensureFresh("alert-kit");
        assertThat(again).as("TTL 未到期复用同一挂载条目，不重复触网").isSameAs(entry);
        assertThat(client.listToolsCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("M06：disable 先提交则无新资格——在飞不打断，后续领取 CAPABILITY_REVOKED")
    void disableCommitsBeforeNewCapabilityClaims() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", TTL_MS);
        manager.enterInvocation("alert-kit");

        manager.disable("alert-kit");

        assertThat(manager.snapshot().servers().get("alert-kit").record().spec().enabled())
                .as("禁用立即在快照生效").isFalse();
        ToolControlPlaneException rejected = catchThrowableOfType(
                () -> manager.enterInvocation("alert-kit"), ToolControlPlaneException.class);
        assertThat(rejected.reason()).isEqualTo(ToolControlReason.CAPABILITY_REVOKED);
        assertThat(rejected.getMessage()).contains("CAPABILITY_REVOKED");
        manager.exitInvocation("alert-kit");
    }

    @Test
    @DisplayName("M07a：在飞未释放前 drain 不完成不关连接——引用释放后恰好关闭一次")
    void drainWaitsForInflightReleaseThenClosesOnce() throws Exception {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", TTL_MS);
        manager.enterInvocation("alert-kit");
        AtomicBoolean drainDone = new AtomicBoolean();
        Thread drainer = new Thread(() -> {
            manager.disableAndWaitDrain("alert-kit", Duration.ofSeconds(5));
            drainDone.set(true);
        });
        drainer.start();
        Thread.sleep(150);
        assertThat(drainDone).as("在飞未释放，drain 不完成").isFalse();
        assertThat(client.closeCalls.get()).as("drain 完成前不关连接").isZero();

        manager.exitInvocation("alert-kit");
        drainer.join(2_000);
        assertThat(drainDone).isTrue();
        assertThat(client.closeCalls.get()).as("引用释放后恰好关闭一次，无泄漏").isEqualTo(1);
    }

    @Test
    @DisplayName("M07b：在飞悬挂时 idle 超时兜底——不永久等待，连接仍关闭")
    void idleTimeoutClosesWithoutPermanentWait() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", TTL_MS);
        manager.enterInvocation("alert-kit");

        long startNanos = System.nanoTime();
        manager.disableAndWaitDrain("alert-kit", Duration.ofMillis(120));
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).as("idle 超时后返回，不永久等待").isLessThan(5_000);
        assertThat(client.closeCalls.get()).isEqualTo(1);
        manager.exitInvocation("alert-kit");
    }

    @Test
    @DisplayName("M10：凭证失效重试有界——无高权限回退、无泄密、次数有界")
    void credentialFailureRetriesBoundedWithoutFallbackOrSecretLeak() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        FakeClient client = new FakeClient(McpTestFixtures.tool("t1"));
        McpMountManager manager = mounted(repo, factory, client, "alert-kit", 100);
        client.listToolsError = new McpServerClient.AuthException(
                "401 rotate failed key=sk-secret-123");
        nowMs.addAndGet(200);

        ToolControlPlaneException failure = catchThrowableOfType(
                () -> manager.ensureFresh("alert-kit"), ToolControlPlaneException.class);
        assertThat(failure.reason()).isEqualTo(ToolControlReason.AUTH_FAILED);
        assertThat(failure.getMessage()).as("异常文案不泄密").doesNotContain("sk-secret-123");
        assertThat(client.listToolsCalls.get()).as("重校验尝试恰为有界上限（3 = 注册校验 1 次 + 重校验 2 次）")
                .isEqualTo(3);
        assertThat(factory.createCalls.get()).as("无高权限回退：不新建任何替代客户端").isEqualTo(1);
        assertThat(manager.snapshot().servers()).as("普通失败不替换健康快照").containsKey("alert-kit");
    }

    @Test
    @DisplayName("M11：任意 stdio 命令与非 http(s) URL 发布前拒绝——零进程零连接零落库")
    void arbitraryStdioCommandAndNonHttpUrlRejectedBeforePublish() {
        MemRegistry repo = new MemRegistry();
        FakeFactory factory = new FakeFactory();
        McpMountManager manager = manager(repo, factory, TTL_MS);

        ToolControlPlaneException stdioRejected = catchThrowableOfType(
                () -> manager.register("evil", "stdio", "rm -rf /", List.of(), null),
                ToolControlPlaneException.class);
        assertThat(stdioRejected.reason()).isEqualTo(ToolControlReason.POLICY_DENIED);
        ToolControlPlaneException urlRejected = catchThrowableOfType(
                () -> manager.register("ext", HTTP, "ftp://internal-host:9000/mcp", List.of(), null),
                ToolControlPlaneException.class);
        assertThat(urlRejected.reason()).isEqualTo(ToolControlReason.POLICY_DENIED);

        assertThat(factory.createCalls.get()).as("未生成任何进程/连接尝试").isZero();
        assertThat(repo.rows).as("拒绝面不落库").isEmpty();
    }

    @Test
    @DisplayName("M12：重启从 PG 恢复一致 revision——disabled 不复活，重复注册幂等顶替")
    void recoverFromRegistryKeepsDisabledDormantAndReRegisterIdempotent() {
        MemRegistry repo = new MemRegistry();
        repo.seed("server-a", true, 1);
        repo.seed("server-b", false, 2);
        FakeFactory factory = new FakeFactory();
        factory.suppliers.put("server-a", () -> new FakeClient(McpTestFixtures.tool("t1")));
        McpMountManager manager = manager(repo, factory, TTL_MS);

        manager.recover();

        assertThat(manager.snapshot().revision()).as("revision 与最大 generation 一致").isEqualTo(2);
        assertThat(factory.created).as("只有 enabled 的 server 建了连接").hasSize(1);
        assertThat(manager.snapshot().servers()).containsOnlyKeys("server-a", "server-b");
        assertThat(manager.snapshot().servers().get("server-b").client())
                .as("disabled 不复活：无连接资源").isNull();
        assertThat(manager.status("server-b")).map(MountStatus::state)
                .contains(ServerState.DISABLED);

        MountStatus remounted = manager.register("server-a", HTTP, "http://new/mcp", List.of(), null);

        assertThat(remounted.state()).isEqualTo(ServerState.MOUNTED);
        assertThat(remounted.generation()).isEqualTo(3);
        assertThat(manager.snapshot().revision()).isEqualTo(3);
        assertThat(repo.rows).as("同名幂等顶替，不产生重复行").hasSize(2);
        assertThat(factory.created.get(0).closeCalls.get()).as("配置变更后旧连接关闭").isEqualTo(1);
        assertThat(factory.created.get(1).closeCalls.get()).as("新连接在用不关").isZero();
    }
}

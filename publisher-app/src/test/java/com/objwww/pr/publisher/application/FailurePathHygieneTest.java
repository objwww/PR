package com.objwww.pr.publisher.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.domain.service.PublishOutcome;
import com.objwww.pr.publisher.fakes.FakePayloadReader;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.publisher.fakes.StubGitHubWriteAdapter;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import com.objwww.pr.publisher.infrastructure.github.GitHubWriteAdapter;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.PublicationResourceState;
import com.objwww.pr.shared.PublicationResourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * EX-30 单测形态（M2 方案 §11/L4，§4.6）：失败路径的 last_error / 事件 payload / 日志
 * 内容扫描——不得含 token、Authorization、完整敏感响应头。
 *
 * <p>手法：① 写路径走真实 {@link GitHubWriteAdapter} + WireMock，token 真实流经 HTTP 层
 * （响应还携带敏感样子的回显头），随后扫描落账面；② drift 失败路径（探针异常/连续失败
 * 告警/权限告警）用 logback {@link ListAppender} 捕获日志后扫描（仿 SEC05 appender 思路）。
 */
class FailurePathHygieneTest {

    /** 仅供泄漏扫描识别的哨兵 token；若任何落账/日志面出现该串即判泄漏 */
    private static final String SECRET = "ghs_unitTestSentinelToken123";

    private WireMockServer wiremock;
    private final List<Logger> capturedLoggers = new ArrayList<>();
    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int i = 0; i < appenders.size(); i++) {
            capturedLoggers.get(i).detachAppender(appenders.get(i));
        }
        if (wiremock != null) {
            wiremock.stop();
        }
    }

    private ListAppender<ILoggingEvent> captureLogs(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        capturedLoggers.add(logger);
        appenders.add(appender);
        return appender;
    }

    /** 统一哨兵扫描：任一表面含 token 值 / Authorization / Bearer / 敏感响应头名即失败 */
    private static void assertNoSecretSurfaces(Iterable<String> surfaces) {
        for (String surface : surfaces) {
            if (surface == null) {
                continue;
            }
            String lower = surface.toLowerCase(Locale.ROOT);
            assertThat(lower)
                    .as("失败路径输出不得含凭证/鉴权/敏感头信息: %s", surface)
                    .doesNotContain(SECRET.toLowerCase(Locale.ROOT))
                    .doesNotContain("bearer")
                    .doesNotContain("authorization")
                    .doesNotContain("x-secret-echo");
        }
    }

    @Test
    void writePathFailureSurfacesCarryNoSecrets() {
        // 真实 adapter + WireMock：403 响应体带 message、响应头携带敏感回显头——
        // token 真实发出过，落账面不许回带任何凭证/鉴权/原始头信息
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        wiremock.stubFor(post(urlEqualTo("/repos/octo/demo/check-runs"))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("X-Secret-Echo", SECRET)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Resource not accessible by integration\"}")));
        CredentialBroker broker = new CredentialBroker() {
            @Override public String token(TokenScope scope) { return SECRET; }
            @Override public String token(long installationId, TokenScope scope) { return SECRET; }
        };
        GitHubWriteAdapter adapter = new GitHubWriteAdapter(
                broker, wiremock.baseUrl(), new ObjectMapper(), Duration.ofSeconds(2));
        FakePublicationStore store = new FakePublicationStore();
        FakePayloadReader payloadReader = new FakePayloadReader();
        ListAppender<ILoggingEvent> logs = captureLogs(FencedPublicationExecutor.class);
        FencedPublicationExecutor executor = new FencedPublicationExecutor(adapter, store,
                payloadReader, List.of(new CreateCheckHandler()), Duration.ofSeconds(60), 3,
                TestFixtures.INSTALLATION_ID);
        ClaimedCommand command = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(command);
        payloadReader.put(command.payloadHash(), TestFixtures.checkPayload(command));

        assertThat(executor.execute(command)).isEqualTo(PublishOutcome.FAILED_TERMINAL);

        List<String> surfaces = new ArrayList<>();
        surfaces.addAll(store.errorCodes.values()); // last_error 面
        store.events.forEach(e -> {                 // 事件 payload 面
            surfaces.add(e.eventType().name());
            surfaces.add(String.valueOf(e.payload()));
        });
        logs.list.forEach(e -> surfaces.add(e.getFormattedMessage())); // 日志面
        assertThat(surfaces).isNotEmpty(); // 确有失败路径产出被扫描（防空跑假绿）
        assertNoSecretSurfaces(surfaces);
    }

    @Test
    void driftFailureLogsAndEventsCarryNoSecrets() {
        // drift 侧失败路径：探针编排异常 + 连续失败达阈值告警 + 普通 403 权限告警，
        // 日志与事件 payload 全扫描
        FakePublicationStore store = new FakePublicationStore();
        FakePayloadReader payloadReader = new FakePayloadReader();
        ListAppender<ILoggingEvent> logs = captureLogs(DriftReconciler.class);
        List<com.objwww.pr.shared.ExecutionEvent> appended = new ArrayList<>();
        ExecutionEventAppender appender = appended::add;

        FencedPublicationExecutor throwingExecutor = new FencedPublicationExecutor(
                new StubGitHubWriteAdapter(), store, payloadReader, List.of(),
                Duration.ofSeconds(1), 1, TestFixtures.INSTALLATION_ID) {
            @Override
            public ReconcileVerdict reconcile(ClaimedCommand command) {
                throw new IllegalStateException("payload 缺必需字段: repo");
            }
        };
        DriftReconciler reconciler = new DriftReconciler(store, throwingExecutor, payloadReader,
                appender, List.of(new CreateCheckHandler()), 50, Duration.ofMinutes(60), 8, 2, 0, 0);
        DriftCheckTarget target = driftTarget(store, payloadReader);
        reconciler.runOnce(); // error_count=1
        store.dueDriftChecks.add(target);
        reconciler.runOnce(); // error_count=2 = 阈值 → RECONCILER_DEGRADED 事件 + error 日志

        List<String> surfaces = new ArrayList<>();
        store.events.forEach(e -> surfaces.add(String.valueOf(e.payload())));
        appended.forEach(e -> surfaces.add(String.valueOf(e.payload())));
        logs.list.forEach(e -> {
            surfaces.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                surfaces.add(e.getThrowableProxy().getClassName()
                        + ":" + e.getThrowableProxy().getMessage());
            }
        });
        assertThat(surfaces).isNotEmpty();
        assertThat(logs.list).isNotEmpty(); // 确有日志被扫描
        assertNoSecretSurfaces(surfaces);
    }

    private DriftCheckTarget driftTarget(FakePublicationStore store, FakePayloadReader reader) {
        ClaimedCommand command = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.CONFIRMED, 0, 3);
        reader.put(command.payloadHash(), TestFixtures.checkPayload(command));
        DriftCheckTarget target = new DriftCheckTarget(UUID.randomUUID(),
                PublicationResourceType.CHECK_RUN, "555", "http://x/555",
                command.operationId().toString(), PublicationResourceState.PRESENT, 0, command);
        store.resourceStates.put(target.resourceId(), PublicationResourceState.PRESENT);
        store.checkErrorCounts.put(target.resourceId(), 0);
        store.dueDriftChecks.add(target);
        return target;
    }
}

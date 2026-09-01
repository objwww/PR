package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.publisher.domain.model.RepairRequestDraft;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresExecutionEventAppender;
import com.objwww.pr.publisher.infrastructure.persistence.PostgresPublicationStore;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RepairPolicyTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CT-24：一资源一活跃修复单，并验证 drift event + request 的真实事务原子性。 */
class CT24RepairRequestAtomicityIT extends PostgresITBase {

    private static final String REPO = "objwww/mall";
    private WireMockServer wiremock;
    private ItHarness harness;
    private ReviewRun run;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
        run = harness.dispatchOpened(ItHarness.prEvent("ct24", 2024L, REPO, 24,
                        "head" + "2".repeat(36), "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + REPO + "/pulls/24/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2001}")));
        harness.newClaimer().runOnce();
        resourceId = adminJdbc.sql("SELECT id FROM publication_resource WHERE resource_type='CHECK_RUN'")
                .query(UUID.class).single();
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void concurrentInsertAllowsExactlyOneActiveRequest() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> insertRequest(start));
            var b = pool.submit(() -> insertRequest(start));
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(count("repair_request")).isEqualTo(1);
    }

    @Test
    void secondEventFailureRollsBackResourceEventAndRequest() {
        var delegate = new PostgresExecutionEventAppender(publisherJdbc, OM);
        AtomicInteger calls = new AtomicInteger();
        var store = new PostgresPublicationStore(publisherJdbc, publisherTx, event -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("inject-second-event-failure");
            delegate.append(event);
        });
        RepairRequestDraft draft = new RepairRequestDraft(UUID.randomUUID(), resourceId,
                PublicationResourceType.CHECK_RUN, RepairPolicyTier.AUTO, 5, Instant.now());

        assertThatThrownBy(() -> store.markMissingWithRepair(resourceId, Duration.ofHours(6),
                event(ExecutionEventType.PUBLICATION_DRIFT_DETECTED), draft,
                event(ExecutionEventType.REPAIR_REQUESTED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inject-second-event-failure");

        assertThat(adminJdbc.sql("SELECT state FROM publication_resource WHERE id=:id")
                .param("id", resourceId).query(String.class).single()).isEqualTo("PRESENT");
        assertThat(count("repair_request")).isZero();
        assertThat(adminJdbc.sql("SELECT count(*) FROM execution_event WHERE event_type IN "
                        + "('PUBLICATION_DRIFT_DETECTED','REPAIR_REQUESTED')")
                .query(Long.class).single()).isZero();
    }

    private int insertRequest(CountDownLatch start) {
        try {
            start.await();
            return publisherJdbc.sql("""
                    INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,
                        state,max_attempts,created_at,updated_at)
                    VALUES (:id,:resource,'CHECK_RUN','AUTO','PENDING',5,now(),now())
                    ON CONFLICT DO NOTHING
                    """).param("id", UUID.randomUUID()).param("resource", resourceId).update();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ExecutionEvent event(ExecutionEventType type) {
        return new ExecutionEvent(UUID.randomUUID(), run.getId(), run.getPrRevisionId(), null, null,
                type, 1, null, run.getId(), "publisher-app", Map.of("resource_id", resourceId.toString()),
                Instant.now());
    }
}

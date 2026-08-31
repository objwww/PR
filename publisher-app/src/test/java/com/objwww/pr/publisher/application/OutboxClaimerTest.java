package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.handler.CreateCheckHandler;
import com.objwww.pr.publisher.domain.handler.PublicationHandler;
import com.objwww.pr.publisher.domain.handler.PublishReviewHandler;
import com.objwww.pr.publisher.domain.handler.UpdateCheckHandler;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.fakes.FakePayloadReader;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.publisher.fakes.StubGitHubWriteAdapter;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.TypedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OutboxClaimer：领取 → 逐条执行；单条失败不阻塞整批；空跑返回 0。
 */
class OutboxClaimerTest {

    private FakePublicationStore store;
    private FakePayloadReader payloadReader;
    private StubGitHubWriteAdapter github;
    private OutboxClaimer claimer;

    @BeforeEach
    void setUp() {
        store = new FakePublicationStore();
        payloadReader = new FakePayloadReader();
        github = new StubGitHubWriteAdapter();
        List<PublicationHandler> handlers = List.of(
                new CreateCheckHandler(), new UpdateCheckHandler(), new PublishReviewHandler());
        FencedPublicationExecutor executor = new FencedPublicationExecutor(
                github, store, payloadReader, handlers, Duration.ofSeconds(60), 3,
                TestFixtures.INSTALLATION_ID);
        claimer = new OutboxClaimer(store, executor, "publisher-test", Duration.ofSeconds(60),
                10, 50, 50);
    }

    @Test
    void claimsAndExecutesInSequenceOrder() {
        // 同 PR 两条命令按 sequence 顺序执行：seq1 先 CONFIRMED，seq2 才能过跳号检测
        ClaimedCommand first = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        ClaimedCommand second = TestFixtures.command(CommandType.PUBLISH_REVIEW, 2, 1,
                OutboxState.PENDING, 0, 3);
        store.put(first);
        store.put(second);
        store.claimQueue.add(first);
        store.claimQueue.add(second);
        payloadReader.put(first.payloadHash(), TestFixtures.checkPayload(first));
        payloadReader.put(second.payloadHash(), TestFixtures.reviewPayload(second));
        github.respondWrite(TypedResponse.ofObject(201, Map.of("id", 1)));
        github.respondWrite(TypedResponse.ofObject(200, Map.of("id", 2)));

        int processed = claimer.runOnce();

        assertEquals(2, processed);
        assertEquals(OutboxState.CONFIRMED, store.stateOf(first).state());
        assertEquals(OutboxState.CONFIRMED, store.stateOf(second).state());
        assertEquals(2, store.cursor.lastResolvedSequence()); // 游标推进到 2（保序）
    }

    @Test
    void singleFailureDoesNotBlockBatch() {
        ClaimedCommand bad = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.PENDING, 0, 3);
        store.put(bad);
        store.claimQueue.add(bad);
        // payload 缺失 → fail-closed FAILED_TERMINAL，不抛出
        int processed = claimer.runOnce();
        assertEquals(1, processed);
        assertEquals(OutboxState.FAILED_TERMINAL, store.stateOf(bad).state());
    }

    @Test
    void idleWhenNothingToClaim() {
        assertEquals(0, claimer.runOnce());
        assertTrue(github.writeRequests.isEmpty());
    }
}

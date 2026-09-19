package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.application.approval.ApprovalRequestService;
import com.objwww.pr.control.alert.domain.mutation.ResolvedResource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApprovalQueueFollowUp 单测（BA-171，fake 端口复用 IntentResourceResolverTest 同包件）：
 * 意图落账后的审批推进——解析命中 → 自动铸 approval_request；Resolver miss →
 * fail-closed（INTENT_RESOLVE_FAILED 事件已落，审批队列零触达，意图留 OPEN 未解析）。
 */
class ApprovalQueueFollowUpTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-18T02:00:00Z"),
            ZoneOffset.UTC);
    private static final ResolvedResource CHECKOUT = new ResolvedResource(
            "res://demo/checkout", "checkout", "demo", "payments", "service", 3, Map.of());

    private static IntentResourceResolver resolverService(
            IntentResourceResolverTest.FakeResolver resolver,
            IntentResourceResolverTest.FakeStore store,
            IntentResourceResolverTest.FakeEvents events) {
        return new IntentResourceResolver(resolver, store, events,
                new IntentResourceResolverTest.DirectTx(), "pb-prod-v1", FIXED);
    }

    @Test
    void ba171_q01_解析命中_自动铸审批单() {
        UUID intentId = UUID.randomUUID();
        IntentResourceResolverTest.FakeResolver resolver =
                new IntentResourceResolverTest.FakeResolver();
        resolver.table.put("checkout", CHECKOUT);
        IntentResourceResolverTest.FakeStore store =
                new IntentResourceResolverTest.FakeStore();
        store.seed(intentId, UUID.randomUUID());
        IntentResourceResolverTest.FakeEvents events =
                new IntentResourceResolverTest.FakeEvents();
        ApprovalRequestService approvalRequests =
                org.mockito.Mockito.mock(ApprovalRequestService.class);

        new ApprovalQueueFollowUp(resolverService(resolver, store, events), approvalRequests)
                .onIntentRecorded(intentId, "checkout");

        // 解析成功（快照锚写入）→ 审批请求铸造以同 intentId 触发
        assertThat(store.rows.get(intentId).resolvedResourceUid())
                .isEqualTo("res://demo/checkout");
        org.mockito.Mockito.verify(approvalRequests).request(intentId);
        assertThat(events.rows.stream().map(IntentResourceResolverTest.EventRow::type))
                .containsExactly("INTENT_RESOURCE_RESOLVED");
    }

    @Test
    void ba171_q02_ResolverMiss_failClosed_审批队列零触达() {
        UUID intentId = UUID.randomUUID();
        IntentResourceResolverTest.FakeStore store =
                new IntentResourceResolverTest.FakeStore();
        store.seed(intentId, UUID.randomUUID());
        IntentResourceResolverTest.FakeEvents events =
                new IntentResourceResolverTest.FakeEvents();
        ApprovalRequestService approvalRequests =
                org.mockito.Mockito.mock(ApprovalRequestService.class);

        new ApprovalQueueFollowUp(resolverService(
                new IntentResourceResolverTest.FakeResolver(), store, events),
                approvalRequests).onIntentRecorded(intentId, "ghost-service");

        // fail-closed：意图保持未解析、事件留痕、审批请求永不铸造（不猜不补）
        assertThat(store.rows.get(intentId).resolvedResourceUid()).isNull();
        assertThat(events.rows.stream().map(IntentResourceResolverTest.EventRow::type))
                .containsExactly("INTENT_RESOLVE_FAILED");
        org.mockito.Mockito.verifyNoInteractions(approvalRequests);
    }
}

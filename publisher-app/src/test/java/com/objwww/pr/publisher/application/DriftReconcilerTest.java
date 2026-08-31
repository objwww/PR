package com.objwww.pr.publisher.application;

import com.objwww.pr.publisher.domain.handler.ReconcileVerdict;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.service.FencedPublicationExecutor;
import com.objwww.pr.publisher.fakes.FakePayloadReader;
import com.objwww.pr.publisher.fakes.FakePublicationStore;
import com.objwww.pr.publisher.fakes.StubGitHubWriteAdapter;
import com.objwww.pr.publisher.fakes.TestFixtures;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.ExecutionEventType;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.PublicationResourceState;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.TypedReadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-13（M1 方案 §11/L1）：Drift 判定全分支——
 * 存在→PRESENT 刷新；404+sanity 通过→MISSING+单次事件；404+sanity 失败→UNKNOWN+权限告警
 * （不标 MISSING）；5xx→不动+error_count+1+阈值告警；已 MISSING 重复扫描不重复发事件（ST-22）。
 */
class DriftReconcilerTest {

    /** 探针桩：整体覆写 reconcile/sanityRead，不触网（UT 只测判定树，不测 HTTP 编排） */
    static class StubDriftExecutor extends FencedPublicationExecutor {
        ReconcileVerdict verdict = ReconcileVerdict.found("555", "http://x/555");
        boolean sane = true;
        int reconcileCalls;
        int sanityCalls;

        StubDriftExecutor() {
            super(new StubGitHubWriteAdapter(), new FakePublicationStore(),
                    new FakePayloadReader(), List.of(), Duration.ofSeconds(1), 1,
                    TestFixtures.INSTALLATION_ID);
        }

        @Override
        public ReconcileVerdict reconcile(ClaimedCommand command) {
            reconcileCalls++;
            return verdict;
        }

        @Override
        public boolean sanityRead(TypedReadRequest sanityProbe) {
            sanityCalls++;
            return sane;
        }
    }

    private FakePublicationStore store;
    private FakePayloadReader payloadReader;
    private StubDriftExecutor executor;
    private List<ExecutionEvent> appendedEvents;
    private DriftReconciler reconciler;

    @BeforeEach
    void setUp() {
        store = new FakePublicationStore();
        payloadReader = new FakePayloadReader();
        executor = new StubDriftExecutor();
        appendedEvents = new ArrayList<>();
        ExecutionEventAppender appender = appendedEvents::add;
        reconciler = new DriftReconciler(store, executor, payloadReader, appender,
                List.of(new com.objwww.pr.publisher.domain.handler.CreateCheckHandler()),
                50, Duration.ofMinutes(60), 8, DriftReconciler.DEFAULT_DEGRADED_THRESHOLD, 0, 0);
    }

    /** PRESENT 资源巡检目标（创建命令 payload 入 fake CAS，sanity 读才能取到 repo） */
    private DriftCheckTarget presentTarget() {
        return target(PublicationResourceState.PRESENT, 0);
    }

    private DriftCheckTarget target(PublicationResourceState state, int errorCount) {
        ClaimedCommand command = TestFixtures.command(CommandType.CREATE_CHECK, 1, 1,
                OutboxState.CONFIRMED, 0, 3);
        payloadReader.put(command.payloadHash(), TestFixtures.checkPayload(command));
        DriftCheckTarget target = new DriftCheckTarget(UUID.randomUUID(),
                PublicationResourceType.CHECK_RUN, "555", "http://x/555",
                command.operationId().toString(), state, errorCount, command);
        store.resourceStates.put(target.resourceId(), state);
        store.checkErrorCounts.put(target.resourceId(), errorCount);
        store.dueDriftChecks.add(target);
        return target;
    }

    @Test
    void foundRefreshesPresent() {
        DriftCheckTarget target = presentTarget();
        executor.verdict = ReconcileVerdict.found("555", "http://x/555");

        assertThat(reconciler.runOnce()).isEqualTo(1);

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.PRESENT);
        assertThat(store.checkErrorCounts.get(target.resourceId())).isZero();
        assertThat(executor.sanityCalls).isZero(); // 在 = 不需要 sanity 读
        assertThat(store.events).isEmpty();
        assertThat(appendedEvents).isEmpty();
    }

    @Test
    void notFoundWithSaneRepoMarksMissingAndEmitsOnce() {
        DriftCheckTarget target = presentTarget();
        executor.verdict = ReconcileVerdict.notFound();
        executor.sane = true;

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.MISSING);
        assertThat(executor.sanityCalls).isEqualTo(1);
        assertThat(store.events).filteredOn(e -> e.eventType() == ExecutionEventType.PUBLICATION_DRIFT_DETECTED)
                .hasSize(1);
    }

    @Test
    void manualPolicy404AlsoGoesThroughSanity() {
        // GET_CHECK_RUN 探针的 404 归 MANUAL_POLICY（M0 §6.3 语义）——drift 视角同为"404 候选"
        DriftCheckTarget target = presentTarget();
        executor.verdict = ReconcileVerdict.manualPolicy();
        executor.sane = true;

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.MISSING);
        assertThat(store.events).filteredOn(e -> e.eventType() == ExecutionEventType.PUBLICATION_DRIFT_DETECTED)
                .hasSize(1);
    }

    @Test
    void notFoundWithFailedSanityMarksUnknownWithPermissionAlert() {
        // EX-17/E2E-18/F-3：sanity 失败 = 无法区分"不存在"与"无权限"——绝不标 MISSING
        DriftCheckTarget target = presentTarget();
        executor.verdict = ReconcileVerdict.notFound();
        executor.sane = false;

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.UNKNOWN);
        assertThat(store.events).filteredOn(
                        e -> e.eventType() == ExecutionEventType.PUBLICATION_DRIFT_PERMISSION_ALERT)
                .hasSize(1);
        assertThat(store.events).filteredOn(
                        e -> e.eventType() == ExecutionEventType.PUBLICATION_DRIFT_DETECTED)
                .isEmpty();
    }

    @Test
    void unknownProbeKeepsStateAndCountsError() {
        // 5xx/超时/429 归 UNKNOWN verdict：状态不动 + error_count+1（EX-14）
        DriftCheckTarget target = presentTarget();
        executor.verdict = ReconcileVerdict.unknown();

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.PRESENT);
        assertThat(store.checkErrorCounts.get(target.resourceId())).isEqualTo(1);
        assertThat(executor.sanityCalls).isZero();
        assertThat(appendedEvents).isEmpty(); // 未达阈值不告警
    }

    @Test
    void degradedAlertAtThreshold() {
        // 措辞修正 #3/EX-14：连续失败 >= 3 必须 ReconcilerDegraded
        DriftCheckTarget target = target(PublicationResourceState.PRESENT, 2);
        executor.verdict = ReconcileVerdict.unknown();

        reconciler.runOnce();

        assertThat(store.checkErrorCounts.get(target.resourceId())).isEqualTo(3);
        assertThat(appendedEvents).filteredOn(
                        e -> e.eventType() == ExecutionEventType.RECONCILER_DEGRADED)
                .hasSize(1);
        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.PRESENT); // 5xx 永不标 MISSING
    }

    @Test
    void alreadyMissingRescanDoesNotReemitEvent() {
        // ST-22：MISSING 低频复核仍 404+sanity 通过 → 保持 MISSING，漂移事件不重复发
        DriftCheckTarget target = target(PublicationResourceState.MISSING, 0);
        executor.verdict = ReconcileVerdict.notFound();
        executor.sane = true;

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.MISSING);
        assertThat(store.events).isEmpty();
        assertThat(appendedEvents).isEmpty();
    }

    @Test
    void missingResourceFoundAgainReturnsToPresent() {
        // 低频复核找回（远端对象重新出现）：回 PRESENT，不再告警
        DriftCheckTarget target = target(PublicationResourceState.MISSING, 0);
        executor.verdict = ReconcileVerdict.found("555", "http://x/555");

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.PRESENT);
        assertThat(store.events).isEmpty();
    }

    @Test
    void missingRescanWithFailedSanityKeepsMissing() {
        // 复核期权限抖动：既有 MISSING 结论不回退成 UNKNOWN（告警首轮已发）
        DriftCheckTarget target = target(PublicationResourceState.MISSING, 0);
        executor.verdict = ReconcileVerdict.notFound();
        executor.sane = false;

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.MISSING);
        assertThat(store.events).isEmpty();
    }

    @Test
    void payloadUnavailableOn404CountsAsCheckError() {
        // 404 候选但本地 payload 不可读 = 无法发起 sanity → 按探测失败退避，不冒充任何事实
        DriftCheckTarget target = presentTarget();
        payloadReaderReset(target); // 换空 reader：该命令 payload 不可读
        executor.verdict = ReconcileVerdict.notFound();

        reconciler.runOnce();

        assertThat(store.resourceStates.get(target.resourceId()))
                .isEqualTo(PublicationResourceState.PRESENT); // 不动
        assertThat(store.checkErrorCounts.get(target.resourceId())).isEqualTo(1);
        assertThat(executor.sanityCalls).isZero(); // 没有 repo 就不发起 sanity
    }

    private void payloadReaderReset(DriftCheckTarget target) {
        // FakePayloadReader 无删除入口：换空 reader 重建 reconciler
        payloadReader = new FakePayloadReader();
        reconciler = new DriftReconciler(store, executor, payloadReader, appendedEvents::add,
                List.of(new com.objwww.pr.publisher.domain.handler.CreateCheckHandler()),
                50, Duration.ofMinutes(60), 8, 3, 0, 0);
    }
}

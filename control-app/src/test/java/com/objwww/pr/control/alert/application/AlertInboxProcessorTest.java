package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.alert.support.TestFixtures;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T05 ST-A01~A05/A07 场景闭环（InMemory + withoutTransaction）：
 * inbox 消费循环 → 拆组 → 软背压 → event 幂等 → incident 水印 → run/task 铸造。
 * ST-A06（finishTask 四分支）归 T06；ST-A08（旧 epoch 拒写）归 T06 worker。
 */
class AlertInboxProcessorTest {

    /** 可推进时钟：ST-A07 要跨过 next_retry_at */
    private static final class MutableClock implements AlertClock {
        volatile Instant now = Instant.parse("2026-09-03T10:00:00Z");

        @Override
        public Instant now() {
            return now;
        }
    }

    private AlertInMemoryStores stores;
    private MutableClock clock;
    private AlertIdentityFactory identity;

    @BeforeEach
    void setUp() {
        stores = new AlertInMemoryStores();
        clock = new MutableClock();
        identity = new AlertIdentityFactory();
    }

    private AlertInboxProcessor newProcessor(DeferredPolicy policy) {
        IncidentProjector projector = new IncidentProjector(stores.events, stores.incidents,
                stores.runs, stores.tasks, identity, policy, SlaPolicy.defaults(), clock);
        return new AlertInboxProcessor(stores.inbox, projector,
                TransactionOperations.withoutTransaction(), clock, "test-owner",
                Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1));
    }

    private AlertInboxProcessor newProcessor() {
        return newProcessor(new DeferredPolicy(1000));
    }

    /** 投一组载荷（落 RECEIVED 行）并跑一次消费 */
    private AlertInboxProcessor.Outcome deliver(String payload) {
        stores.inbox.insert(TestFixtures.inboxRowOf(UUID.randomUUID(), payload));
        return newProcessor().processOnce();
    }

    private Incident soleIncident() {
        assertThat(stores.incidents.all()).hasSize(1);
        return stores.incidents.all().get(0);
    }

    // ------------------------------------------------------------------ ST-A01 全生命周期

    @Test
    @DisplayName("ST-A01 firing→run/task 铸造→resolved 归并 全生命周期")
    void stA01_fullLifecycle() {
        AlertInboxProcessor.Outcome first = deliver(TestFixtures.amGroup("g1", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值")));
        assertThat(first).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        Incident incident = soleIncident();
        assertThat(incident.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(incident.generation()).isZero();
        assertThat(incident.episodeStartedAt()).isEqualTo(Instant.parse("2026-09-03T09:00:00Z"));
        assertThat(incident.receivedCount()).isEqualTo(1);
        assertThat(incident.distinctEventCount()).isEqualTo(1);
        assertThat(stores.events.all()).hasSize(1);

        assertThat(stores.runs.all()).hasSize(1);
        RcaRun run = stores.runs.all().get(0);
        assertThat(run.state()).isEqualTo(RcaRunState.QUEUED);
        assertThat(run.trigger()).isEqualTo(RunTrigger.INITIAL);
        assertThat(run.generation()).isZero();
        assertThat(incident.currentRcaRunId()).isEqualTo(run.id());

        assertThat(stores.tasks.all()).hasSize(1);
        RcaTask task = stores.tasks.all().get(0);
        assertThat(task.taskKey()).isEqualTo(RcaTask.HOLMES_INVESTIGATE);
        assertThat(task.state()).isEqualTo(RcaTaskState.READY);
        assertThat(task.priority()).isEqualTo(SlaPolicy.PRIORITY_CRITICAL);
        assertThat(task.deadlineAt()).isEqualTo(Instant.MAX);

        // 模拟 T06 finishTask：调查完成落 report 语义此处等价于 run/task 终态 + 材料锚定
        finishActiveRun(incident);

        AlertInboxProcessor.Outcome second = deliver(TestFixtures.amGroup("g1", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "resolved", "2026-09-03T09:00:00Z", "错误率超阈值")));
        assertThat(second).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        Incident resolved = soleIncident();
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isEqualTo(clock.now);
        assertThat(resolved.generation()).isZero();
        assertThat(resolved.receivedCount()).isEqualTo(2);
        assertThat(resolved.distinctEventCount()).isEqualTo(2);
        assertThat(stores.runs.all()).hasSize(1);   // resolved 不铸 run
        assertThat(stores.inbox.all())
                .allSatisfy(row -> {
                    assertThat(row.state()).isEqualTo(InboxState.PROCESSED);
                    assertThat(row.decision()).isEqualTo(InboxDecision.ACCEPTED);
                });
    }

    // ------------------------------------------------------------------ ST-A02 混合 severity 单组

    @Test
    @DisplayName("ST-A02 混合 severity 单组：整组落库逐条分流，同 incident 不裂单")
    void stA02_mixedSeverityGroup() {
        AlertInboxProcessor.Outcome outcome = deliver(TestFixtures.amGroup("g2", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值"),
                TestFixtures.alertJson("HighErrorRate", "checkout", "info",
                        "firing", "2026-09-03T09:00:30Z", "错误率超阈值"),
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "resolved", "2026-09-03T08:00:00Z", "错误率超阈值")));
        assertThat(outcome).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        // severity 不参与 incident_key（INV-AM1-4）→ 三条同 incident
        assertThat(stores.incidents.all()).hasSize(1);
        Incident incident = soleIncident();
        assertThat(incident.status()).isEqualTo(IncidentStatus.FIRING);   // 晚到 resolved 不覆盖
        assertThat(incident.receivedCount()).isEqualTo(3);
        assertThat(incident.distinctEventCount()).isEqualTo(3);
        assertThat(stores.events.all()).hasSize(3);

        // run 只铸一次（首条 critical 触发），priority=200
        assertThat(stores.runs.all()).hasSize(1);
        assertThat(stores.tasks.all()).hasSize(1);
        assertThat(stores.tasks.all().get(0).priority()).isEqualTo(SlaPolicy.PRIORITY_CRITICAL);
    }

    // ------------------------------------------------------------------ ST-A03 truncatedAlerts

    @Test
    @DisplayName("ST-A03 truncatedAlerts>0：envelope 审计保留，投影照常")
    void stA03_truncatedAlerts() {
        AlertInboxProcessor.Outcome outcome = deliver(TestFixtures.amGroup("g3", 2,
                TestFixtures.alertJson("HighLatency", "cart", "warning",
                        "firing", "2026-09-03T09:05:00Z", "延迟超阈值")));
        assertThat(outcome).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
        assertThat(stores.inbox.all()).allSatisfy(row ->
                assertThat(row.envelope().truncatedAlerts()).isEqualTo(2));
        assertThat(stores.incidents.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(1);   // 截断不影响组内已有条目投影
    }

    // ------------------------------------------------------------------ ST-A04 firing/resolved 乱序

    @Test
    @DisplayName("ST-A04 乱序：晚到 resolved/晚到 firing 不覆盖更新的 firing")
    void stA04_outOfOrderConvergence() {
        deliver(TestFixtures.amGroup("g4", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:10:00Z", "材料甲")));
        // 晚到 resolved（startsAt 09:05 < 水印 09:10）：只计数
        deliver(TestFixtures.amGroup("g4", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "resolved", "2026-09-03T09:05:00Z", "材料甲")));
        Incident afterLateResolved = soleIncident();
        assertThat(afterLateResolved.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(afterLateResolved.resolvedAt()).isNull();
        assertThat(afterLateResolved.receivedCount()).isEqualTo(2);

        // 晚到 firing（startsAt 09:03 < 水印）：只计数、不铸新 run
        deliver(TestFixtures.amGroup("g4", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:03:00Z", "材料甲")));
        Incident afterLateFiring = soleIncident();
        assertThat(afterLateFiring.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(afterLateFiring.receivedCount()).isEqualTo(3);
        assertThat(afterLateFiring.generation()).isZero();
        assertThat(stores.runs.all()).hasSize(1);
    }

    // ------------------------------------------------------------------ ST-A05 调查中连续材料变化

    @Test
    @DisplayName("ST-A05 RCA 运行中连续三次材料变化 → 只一个 pending，不裂多个 run")
    void stA05_materialChangeDuringActiveRun() {
        deliver(TestFixtures.amGroup("g5", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:00:00Z", "材料一")));
        assertThat(stores.runs.all()).hasSize(1);

        for (int i = 2; i <= 4; i++) {
            deliver(TestFixtures.amGroup("g5", 0,
                    TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                            "firing", "2026-09-03T09:0" + i + ":00Z", "材料" + chinese(i))));
        }

        assertThat(stores.runs.all()).hasSize(1);   // 活跃 run 期间永不新铸
        assertThat(stores.tasks.all()).hasSize(1);
        Incident incident = soleIncident();
        assertThat(incident.receivedCount()).isEqualTo(4);
        assertThat(incident.distinctEventCount()).isEqualTo(4);
        // pending = 最后一次材料（收尾算法据此一次 rerun，§6.7）
        Digest expected = identity.investigationHash(
                java.util.Map.of("alertname", "HighErrorRate", "service", "checkout",
                        "severity", "warning"),
                java.util.Map.of("summary", "材料四", "runbook", "rb-1"));
        assertThat(incident.pendingInvestigationHash()).isEqualTo(expected);
        assertThat(incident.lastInvestigationHash()).isNull();
    }

    private static String chinese(int i) {
        return switch (i) {
            case 2 -> "二";
            case 3 -> "三";
            default -> "四";
        };
    }

    // ------------------------------------------------------------------ ST-A07 洪峰软背压

    @Test
    @DisplayName("ST-A07 洪峰软背压：超限 DEFERRED 审计 + backlog 回落补投")
    void stA07_backlogDeferralAndRequeue() {
        AlertInboxProcessor tight = newProcessor(new DeferredPolicy(0));

        // 组1 投完即 backlog=1(incident)+1(task)=2 > 0
        stores.inbox.insert(TestFixtures.inboxRowOf(UUID.randomUUID(), TestFixtures.amGroup("g6", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值"))));
        assertThat(tight.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        // 组2（另一 incident）：逐条判定 DEFERRED → RETRY_WAIT + decision=DEFERRED，零投影副作用
        UUID group2Id = UUID.randomUUID();
        stores.inbox.insert(TestFixtures.inboxRowOf(group2Id, TestFixtures.amGroup("g7", 0,
                TestFixtures.alertJson("HighLatency", "cart", "warning",
                        "firing", "2026-09-03T09:00:00Z", "延迟超阈值"))));
        assertThat(tight.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.DEFERRED);
        AlertInbox group2 = stores.inbox.findById(group2Id).orElseThrow();
        assertThat(group2.state()).isEqualTo(InboxState.RETRY_WAIT);
        assertThat(group2.decision()).isEqualTo(InboxDecision.DEFERRED);
        assertThat(group2.nextRetryAt()).isNotNull();
        assertThat(stores.events.all()).hasSize(1);          // 组2 零 event
        assertThat(stores.incidents.all()).hasSize(1);      // 零新 incident

        // 未到 next_retry_at：不重领
        clock.now = clock.now.plusSeconds(10);
        assertThat(tight.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.SKIPPED);

        // backlog 回落（run/task 收尾 + incident 归并）+ 时钟越过 next_retry_at → 补投成功
        finishActiveRun(stores.incidents.all().get(0));
        stores.incidents.update(withStatus(stores.incidents.all().get(0), IncidentStatus.RESOLVED));
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(tight.processOnce()).isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        assertThat(stores.inbox.findById(group2Id).orElseThrow().state())
                .isEqualTo(InboxState.PROCESSED);
        assertThat(stores.incidents.all()).hasSize(2);
        assertThat(stores.runs.all()).hasSize(2);   // incident2 的 run 补投铸出
    }

    // ------------------------------------------------------------------ 幂等/升级/新 episode/RERUN

    @Test
    @DisplayName("重复通知只累加：received+1、notification+1，不重铸 run")
    void duplicateNotificationOnlyAccumulates() {
        String payload = TestFixtures.amGroup("g8", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值"));
        deliver(payload);
        deliver(payload);

        Incident incident = soleIncident();
        assertThat(incident.receivedCount()).isEqualTo(2);
        assertThat(incident.distinctEventCount()).isEqualTo(1);
        assertThat(incident.notificationCount()).isEqualTo(1);
        assertThat(stores.events.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(1);
        assertThat(stores.tasks.all()).hasSize(1);
    }

    @Test
    @DisplayName("severity 升级不裂单：同 incident，且升级本身不触发重查（双哈希设计）")
    void severityUpgradeNoSplit() {
        deliver(TestFixtures.amGroup("g9", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值")));
        deliver(TestFixtures.amGroup("g9", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T09:02:00Z", "错误率超阈值")));

        assertThat(stores.incidents.all()).hasSize(1);
        assertThat(stores.runs.all()).hasSize(1);
        // 升级不裂单且不触发重查：pending 仍锚定首轮材料（severity 不参与 investigationHash）
        assertThat(soleIncident().pendingInvestigationHash()).isEqualTo(runMaterialHash());
        assertThat(soleIncident().receivedCount()).isEqualTo(2);
    }

    private Digest runMaterialHash() {
        // 首轮铸造时的 pending = 首条 alert 的 investigationHash（summary 相同 + key 标签相同）
        return identity.investigationHash(
                java.util.Map.of("alertname", "HighErrorRate", "service", "checkout"),
                java.util.Map.of("summary", "错误率超阈值", "runbook", "rb-1"));
    }

    @Test
    @DisplayName("resolved 后 firing 再现：generation+1 新 episode，无条件铸 INITIAL run")
    void refireAfterResolvedStartsNewEpisode() {
        deliver(TestFixtures.amGroup("g10", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值")));
        finishActiveRun(soleIncident());
        deliver(TestFixtures.amGroup("g10", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "resolved", "2026-09-03T09:00:00Z", "错误率超阈值")));
        // 真再现：新 firing 的 startsAt 必须晚于 resolve 的处理时刻（本组时钟 10:00，
        // BA-12①/G0-07 乱序防御以此为锚——早于锚点的 firing 属同 episode 迟到件，只计数）
        deliver(TestFixtures.amGroup("g10", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T10:30:00Z", "错误率超阈值")));

        Incident incident = soleIncident();
        assertThat(incident.status()).isEqualTo(IncidentStatus.FIRING);
        assertThat(incident.generation()).isEqualTo(1);
        assertThat(incident.resolvedAt()).isNull();
        assertThat(incident.episodeStartedAt()).isEqualTo(Instant.parse("2026-09-03T10:30:00Z"));

        assertThat(stores.runs.all()).hasSize(2);
        RcaRun second = stores.runs.all().get(1);
        assertThat(second.trigger()).isEqualTo(RunTrigger.INITIAL);
        assertThat(second.generation()).isEqualTo(1);
        assertThat(incident.currentRcaRunId()).isEqualTo(second.id());
    }

    @Test
    @DisplayName("调查完成后材料变化：铸 RERUN run")
    void rerunAfterFinishedRun() {
        deliver(TestFixtures.amGroup("g11", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:00:00Z", "材料一")));
        Incident incident = soleIncident();
        finishActiveRun(incident);   // lastInvestigationHash=材料一, pending=null

        deliver(TestFixtures.amGroup("g11", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "firing", "2026-09-03T09:40:00Z", "材料二")));

        assertThat(stores.runs.all()).hasSize(2);
        RcaRun rerun = stores.runs.all().get(1);
        assertThat(rerun.trigger()).isEqualTo(RunTrigger.RERUN);
        assertThat(rerun.generation()).isZero();
        // 材料未变的重复 firing 不再铸（重复通知路径已覆盖）；RERUN 材料锚定
        Incident after = soleIncident();
        assertThat(after.lastInvestigationHash()).isNotEqualTo(after.pendingInvestigationHash());
    }

    @Test
    @DisplayName("首见即 resolved：诚实落 RESOLVED，不铸 run")
    void resolvedOnlyFirstSeen() {
        deliver(TestFixtures.amGroup("g12", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "warning",
                        "resolved", "2026-09-03T09:00:00Z", "错误率超阈值")));
        Incident incident = soleIncident();
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.resolvedAt()).isNotNull();
        assertThat(stores.runs.all()).isEmpty();
        assertThat(stores.tasks.all()).isEmpty();
    }

    // ------------------------------------------------------------------ 行终局路径

    @Test
    @DisplayName("缺 alertname：投影拒绝 → DEAD_LETTER 审计")
    void missingAlertnameDeadLetters() {
        String payload = """
                {"version": "4", "receiver": "control-app", "groupKey": "g13", "status": "firing",
                 "alerts": [{"status": "firing",
                             "labels": {"service": "checkout"},
                             "annotations": {}, "startsAt": "2026-09-03T09:00:00Z",
                             "fingerprint": "fp-noalertname"}], "truncatedAlerts": 0}
                """;
        AlertInboxProcessor.Outcome outcome = deliver(payload);
        assertThat(outcome).isEqualTo(AlertInboxProcessor.Outcome.DEAD_LETTER);
        assertThat(stores.inbox.all()).hasSize(1);
        AlertInbox row = stores.inbox.all().get(0);
        assertThat(row.state()).isEqualTo(InboxState.DEAD_LETTER);
        assertThat(row.lastError()).contains("alertname");
        assertThat(stores.events.all()).isEmpty();
        assertThat(stores.incidents.all()).isEmpty();
    }

    @Test
    @DisplayName("载荷腐坏：DEAD_LETTER，不静默吞")
    void corruptPayloadDeadLetters() {
        stores.inbox.insert(TestFixtures.inboxRowOf(UUID.randomUUID(), "{not-json"));
        assertThat(newProcessor().processOnce())
                .isEqualTo(AlertInboxProcessor.Outcome.DEAD_LETTER);
        assertThat(stores.inbox.all().get(0).lastError()).contains("payload-corrupt");
    }

    @Test
    @DisplayName("空组：IGNORED（入口已落 IGNORED 的行不再投影；防御路径）")
    void emptyAlertsIgnored() {
        AlertInboxProcessor.Outcome outcome = deliver(TestFixtures.amGroup("g14", 0));
        assertThat(outcome).isEqualTo(AlertInboxProcessor.Outcome.IGNORED);
        assertThat(stores.inbox.all().get(0).state()).isEqualTo(InboxState.IGNORED);
    }

    @Test
    @DisplayName("无可领行：SKIPPED（空转节流信号）")
    void emptyInboxSkips() {
        assertThat(newProcessor().processOnce())
                .isEqualTo(AlertInboxProcessor.Outcome.SKIPPED);
    }

    // ------------------------------------------------------------------ 测试辅助（模拟 T06 finishTask 语义）

    /** 把活跃 run/task 置终态并锚定材料（lastInvestigationHash=pending，清 pending 与 current 指针） */
    private void finishActiveRun(Incident incident) {
        finishActiveRun(incident, clock.now);
    }

    // ------------------------------------------------------------------ BA-12①/G0-07 同 episode 乱序

    @Test
    @DisplayName("BA-12① 同 episode 内 resolved 先于 firing 到达：迟到 firing 只追加不复活，零新 run")
    void staleFiringWithinEpisodeAfterResolvedOnlyCounts() {
        // t=10:00 首个 firing（episode 起点 09:00）
        assertThat(deliver(TestFixtures.amGroup("g-stale", 0,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T09:00:00Z", "错误率超阈值"))))
                .isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
        assertThat(soleIncident().status()).isEqualTo(IncidentStatus.FIRING);

        // t=11:00 处理 resolved → incident.resolvedAt = 11:00
        clock.now = Instant.parse("2026-09-03T11:00:00Z");
        assertThat(deliver(TestFixtures.amGroup("g-stale", 1,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "resolved", "2026-09-03T09:30:00Z", "错误率超阈值"))))
                .isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);
        assertThat(soleIncident().status()).isEqualTo(IncidentStatus.RESOLVED);

        // t=11:30 迟到 firing：episode 水印内（10:30 >= 09:00）但早于 resolve 时刻（10:30 < 11:00）
        // 旧代码走"再现"分支：generation+1 + 铸 INITIAL run，incident 卡死 FIRING 永不再关
        clock.now = Instant.parse("2026-09-03T11:30:00Z");
        assertThat(deliver(TestFixtures.amGroup("g-stale", 2,
                TestFixtures.alertJson("HighErrorRate", "checkout", "critical",
                        "firing", "2026-09-03T10:30:00Z", "错误率超阈值"))))
                .isEqualTo(AlertInboxProcessor.Outcome.ACCEPTED);

        Incident after = soleIncident();
        assertThat(after.status()).as("迟到 firing 不得复活 incident").isEqualTo(IncidentStatus.RESOLVED);
        assertThat(after.generation()).as("不得进新 episode").isZero();
        assertThat(after.receivedCount()).isEqualTo(3);
        assertThat(after.distinctEventCount()).as("event 仍追加").isEqualTo(3);
        assertThat(stores.events.all()).hasSize(3);
        assertThat(stores.runs.all()).as("零新 run").hasSize(1);
    }

    private void finishActiveRun(Incident incident, Instant at) {
        Optional<RcaRun> active = stores.runs.findActiveByIncidentId(incident.id());
        assertThat(active).as("finishActiveRun 前应有活跃 run").isPresent();
        RcaRun run = active.get();
        RcaTask task = stores.tasks.all().stream()
                .filter(t -> t.runId().equals(run.id())).findFirst().orElseThrow();
        stores.tasks.update(new RcaTask(task.id(), task.runId(), task.taskKey(),
                RcaTaskState.DONE, task.priority(), task.availableAt(), task.readySince(),
                task.deadlineAt(), task.leaseOwner(), task.leaseUntil(), task.leaseEpoch(),
                task.attemptCount(), task.maxAttempts(), task.createdAt(), at));
        stores.runs.update(new RcaRun(run.id(), run.incidentId(), run.generation(),
                run.trigger(), RcaRunState.SUCCEEDED, run.investigationHash(),
                run.createdAt(), at, run.startedAt(), at, run.lastError()));
        Incident fresh = stores.incidents.findById(incident.id()).orElseThrow();
        stores.incidents.update(new Incident(fresh.id(), fresh.incidentKey(), fresh.status(),
                fresh.generation(), fresh.episodeStartedAt(), fresh.lastFiringStartsAt(),
                fresh.resolvedAt(), fresh.pendingInvestigationHash(), null,
                fresh.receivedCount(), fresh.distinctEventCount(), fresh.notificationCount(),
                null, fresh.firstSeenAt(), fresh.lastEventAt(), fresh.createdAt(), at));
    }

    private Incident withStatus(Incident i, IncidentStatus status) {
        return new Incident(i.id(), i.incidentKey(), status, i.generation(),
                i.episodeStartedAt(), i.lastFiringStartsAt(),
                status == IncidentStatus.RESOLVED ? clock.now : null,
                i.lastInvestigationHash(), i.pendingInvestigationHash(),
                i.receivedCount(), i.distinctEventCount(), i.notificationCount(),
                i.currentRcaRunId(), i.firstSeenAt(), i.lastEventAt(), i.createdAt(), clock.now);
    }
}

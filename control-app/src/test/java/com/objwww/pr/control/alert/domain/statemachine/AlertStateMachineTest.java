package com.objwww.pr.control.alert.domain.statemachine;

import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-A01~A04：四台状态机 + 决策枚举反射穷举（T02 验收"状态迁移反射穷举全绿"）。
 *
 * <p>期望迁移集直接抄自技术方案 §6.1/§6.7 与 V7 CHECK——代码若漂移，穷举比对在此红。
 */
class AlertStateMachineTest {

    /** 穷举比对：全 (from,to) 组合，allowed 必须精确等于期望集 */
    private static <S extends Enum<S>> void assertExhaustive(Class<S> type,
            Map<S, Set<S>> expected, BiPredicate<S, S> machine) {
        for (S from : type.getEnumConstants()) {
            for (S to : type.getEnumConstants()) {
                boolean want = expected.getOrDefault(from, Set.of()).contains(to);
                assertThat(machine.test(from, to))
                        .as("%s -> %s 应为 %s", from, to, want ? "允许" : "拒绝")
                        .isEqualTo(want);
            }
        }
    }

    /** 允许对之外的组合 requireTransition 必须抛 IllegalTransitionException */
    private static <S extends Enum<S>> void assertIllegalThrows(Class<S> type,
            Map<S, Set<S>> expected, java.util.function.BiConsumer<S, S> require) {
        for (S from : type.getEnumConstants()) {
            for (S to : type.getEnumConstants()) {
                if (!expected.getOrDefault(from, Set.of()).contains(to)) {
                    assertThatThrownBy(() -> require.accept(from, to))
                            .as("%s -> %s 必须抛 IllegalTransitionException", from, to)
                            .isInstanceOf(IllegalTransitionException.class);
                }
            }
        }
    }

    // ---------------- UT-A01 Incident（二态事实 + generation） ----------------

    @Test
    void utA01IncidentMachineExhaustive() {
        Map<com.objwww.pr.control.alert.domain.model.IncidentStatus,
                Set<com.objwww.pr.control.alert.domain.model.IncidentStatus>> expected = Map.of(
                com.objwww.pr.control.alert.domain.model.IncidentStatus.FIRING,
                        Set.of(com.objwww.pr.control.alert.domain.model.IncidentStatus.RESOLVED),
                com.objwww.pr.control.alert.domain.model.IncidentStatus.RESOLVED,
                        Set.of(com.objwww.pr.control.alert.domain.model.IncidentStatus.FIRING));

        assertExhaustive(com.objwww.pr.control.alert.domain.model.IncidentStatus.class,
                expected, IncidentStateMachine::allowed);
        assertIllegalThrows(com.objwww.pr.control.alert.domain.model.IncidentStatus.class,
                expected, IncidentStateMachine::requireTransition);
    }

    @Test
    void utA01GenerationBumpsOnlyOnRefire() {
        var F = com.objwww.pr.control.alert.domain.model.IncidentStatus.FIRING;
        var R = com.objwww.pr.control.alert.domain.model.IncidentStatus.RESOLVED;

        // firing→resolved：同 episode 收尾，generation 不动
        assertThat(IncidentStateMachine.nextGeneration(F, R, 3)).isEqualTo(3);
        // resolved→firing：firing 再现 = 新 episode，generation+1（§6.7）
        assertThat(IncidentStateMachine.nextGeneration(R, F, 3)).isEqualTo(4);
    }

    // ---------------- UT-A02 RcaRun（AM1 六态 + AM4 三态 = 九态） ----------------

    @Test
    void utA02RcaRunMachineExhaustive() {
        var Q = com.objwww.pr.control.alert.domain.model.RcaRunState.QUEUED;
        var R = com.objwww.pr.control.alert.domain.model.RcaRunState.RUNNING;
        var S = com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED;
        var F = com.objwww.pr.control.alert.domain.model.RcaRunState.FAILED;
        var C = com.objwww.pr.control.alert.domain.model.RcaRunState.CANCELLED;
        var P = com.objwww.pr.control.alert.domain.model.RcaRunState.SUPERSEDED;
        var RP = com.objwww.pr.control.alert.domain.model.RcaRunState.REPORTING;
        var PT = com.objwww.pr.control.alert.domain.model.RcaRunState.PARTIAL;
        var EX = com.objwww.pr.control.alert.domain.model.RcaRunState.EXPIRED;

        Map<com.objwww.pr.control.alert.domain.model.RcaRunState,
                Set<com.objwww.pr.control.alert.domain.model.RcaRunState>> expected = Map.of(
                Q, Set.of(R, RP, C, P, S, F),   // QUEUED→SUCCEEDED/FAILED：finishTask 退化路径（G0-06）；→REPORTING 同款退化 AM4 形态
                R, Set.of(RP, S, F, PT, EX, C, P),
                RP, Set.of(S, F, PT, EX, C, P));

        assertExhaustive(com.objwww.pr.control.alert.domain.model.RcaRunState.class,
                expected, RcaRunStateMachine::allowed);
        assertIllegalThrows(com.objwww.pr.control.alert.domain.model.RcaRunState.class,
                expected, RcaRunStateMachine::requireTransition);

        // 终态无出边（穷举已证），活跃判定对齐 V12 部分唯一索引谓词（M4-02 同步）：REPORTING 计活跃
        assertThat(Q.isActive()).isTrue();
        assertThat(R.isActive()).isTrue();
        assertThat(RP.isActive()).isTrue();
        assertThat(S.isActive()).isFalse();
        assertThat(PT.isActive()).isFalse();
        assertThat(EX.isActive()).isFalse();
    }

    // ---------------- UT-A03 RcaTask（AM1 六态 + AM4 五态 = 十一态） ----------------

    @Test
    void utA03RcaTaskMachineExhaustive() {
        var RD = com.objwww.pr.control.alert.domain.model.RcaTaskState.READY;
        var L = com.objwww.pr.control.alert.domain.model.RcaTaskState.LEASED;
        var RW = com.objwww.pr.control.alert.domain.model.RcaTaskState.RETRY_WAIT;
        var D = com.objwww.pr.control.alert.domain.model.RcaTaskState.DONE;
        var C = com.objwww.pr.control.alert.domain.model.RcaTaskState.CANCELLED;
        var X = com.objwww.pr.control.alert.domain.model.RcaTaskState.DEAD;
        var BL = com.objwww.pr.control.alert.domain.model.RcaTaskState.BLOCKED;
        var RN = com.objwww.pr.control.alert.domain.model.RcaTaskState.RUNNING;
        var SK = com.objwww.pr.control.alert.domain.model.RcaTaskState.SKIPPED;
        var FT = com.objwww.pr.control.alert.domain.model.RcaTaskState.FAILED_TERMINAL;
        var ST = com.objwww.pr.control.alert.domain.model.RcaTaskState.STALE;

        Map<com.objwww.pr.control.alert.domain.model.RcaTaskState,
                Set<com.objwww.pr.control.alert.domain.model.RcaTaskState>> expected = Map.of(
                RD, Set.of(L, C, X),
                L, Set.of(RD, RN, RW, D, C, X, ST),
                RN, Set.of(RD, RW, D, C, X, ST),
                BL, Set.of(RD, SK, C),
                RW, Set.of(RD, X, C));

        assertExhaustive(com.objwww.pr.control.alert.domain.model.RcaTaskState.class,
                expected, RcaTaskStateMachine::allowed);
        assertIllegalThrows(com.objwww.pr.control.alert.domain.model.RcaTaskState.class,
                expected, RcaTaskStateMachine::requireTransition);

        // AM4 新终态锚点：FAILED_TERMINAL 确定性失败不重试、STALE 不复活、SKIPPED 不回流
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(FT, RN))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(ST, RD))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(SK, BL))
                .isInstanceOf(IllegalTransitionException.class);
        // M4-07 generation fence 入边：LEASED/RUNNING→STALE（在途工作对死 run 收敛，不复活）；
        // READY/RETRY_WAIT/BLOCKED 不入 STALE——未领取工作靠 claim 栅栏隔离，回收面只处理 LEASED
        assertThat(RcaTaskStateMachine.allowed(L, ST)).isTrue();
        assertThat(RcaTaskStateMachine.allowed(RN, ST)).isTrue();
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(RD, ST))
                .isInstanceOf(IllegalTransitionException.class);
        // WAITING_APPROVAL 属 AM5，本期枚举不引入（评审 v1.1 修正④）
        assertThat(com.objwww.pr.control.alert.domain.model.RcaTaskState.values())
                .containsExactly(com.objwww.pr.control.alert.domain.model.RcaTaskState.READY,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.LEASED,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.RETRY_WAIT,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.DONE,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.CANCELLED,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.DEAD,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.BLOCKED,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.RUNNING,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.SKIPPED,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.FAILED_TERMINAL,
                        com.objwww.pr.control.alert.domain.model.RcaTaskState.STALE);
    }

    // ---------------- UT-A04 Inbox 六态 + 决策枚举三分 ----------------

    @Test
    void utA04InboxMachineExhaustiveAndDecisionEnumClosed() {
        var RC = com.objwww.pr.control.alert.domain.model.InboxState.RECEIVED;
        var PG = com.objwww.pr.control.alert.domain.model.InboxState.PROCESSING;
        var RW = com.objwww.pr.control.alert.domain.model.InboxState.RETRY_WAIT;
        var P = com.objwww.pr.control.alert.domain.model.InboxState.PROCESSED;
        var I = com.objwww.pr.control.alert.domain.model.InboxState.IGNORED;
        var DL = com.objwww.pr.control.alert.domain.model.InboxState.DEAD_LETTER;

        Map<com.objwww.pr.control.alert.domain.model.InboxState,
                Set<com.objwww.pr.control.alert.domain.model.InboxState>> expected = Map.of(
                RC, Set.of(PG, I),                    // RECEIVED→IGNORED：空组直落（EX-A10）
                PG, Set.of(P, RW, I, DL, RC),         // PROCESSING→RECEIVED：仅崩溃回收路径
                RW, Set.of(PG, DL));

        assertExhaustive(com.objwww.pr.control.alert.domain.model.InboxState.class,
                expected, InboxStateMachine::allowed);
        assertIllegalThrows(com.objwww.pr.control.alert.domain.model.InboxState.class,
                expected, InboxStateMachine::requireTransition);

        // 准入三分（评审 #2）：精确三值，SUPPRESSED 本期不产生但枚举必须预留
        assertThat(com.objwww.pr.control.alert.domain.model.InboxDecision.values())
                .containsExactly(
                        com.objwww.pr.control.alert.domain.model.InboxDecision.ACCEPTED,
                        com.objwww.pr.control.alert.domain.model.InboxDecision.DEFERRED,
                        com.objwww.pr.control.alert.domain.model.InboxDecision.SUPPRESSED);
    }

    // ---------------- UT-A05 ReportPublication（六态，M3-09 冻结） ----------------

    @Test
    void utA05PublicationMachineExhaustive() {
        var P = com.objwww.pr.control.alert.domain.model.PublicationState.PENDING;
        var RDY = com.objwww.pr.control.alert.domain.model.PublicationState.READY;
        var S = com.objwww.pr.control.alert.domain.model.PublicationState.SENT;
        var RW = com.objwww.pr.control.alert.domain.model.PublicationState.RETRY_WAIT;
        var D = com.objwww.pr.control.alert.domain.model.PublicationState.DEAD;
        var SUP = com.objwww.pr.control.alert.domain.model.PublicationState.SUPPRESSED;

        Map<com.objwww.pr.control.alert.domain.model.PublicationState,
                Set<com.objwww.pr.control.alert.domain.model.PublicationState>> expected = Map.of(
                P, Set.of(RDY, SUP),            // 出生后就绪 / 出生即抑制（不发布的报告）
                RDY, Set.of(S, RW, D),          // 送达 / 429·5xx 退避 / 4xx·耗尽
                RW, Set.of(RDY, D));            // 退避到期重投 / 退避耗尽

        assertExhaustive(com.objwww.pr.control.alert.domain.model.PublicationState.class,
                expected, ReportPublicationStateMachine::allowed);
        assertIllegalThrows(com.objwww.pr.control.alert.domain.model.PublicationState.class,
                expected, ReportPublicationStateMachine::requireTransition);
    }

    @Test
    void utA05PublicationTerminalSemantics() {
        // 三终态无出边（穷举已证非法回流），此处锚定最危险的"SENT 复活"与"DEAD 自动重发"
        assertThatThrownBy(() -> ReportPublicationStateMachine.requireTransition(
                com.objwww.pr.control.alert.domain.model.PublicationState.SENT,
                com.objwww.pr.control.alert.domain.model.PublicationState.READY))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> ReportPublicationStateMachine.requireTransition(
                com.objwww.pr.control.alert.domain.model.PublicationState.DEAD,
                com.objwww.pr.control.alert.domain.model.PublicationState.RETRY_WAIT))
                .isInstanceOf(IllegalTransitionException.class);
        // UNKNOWN 不自动重发：没有 UNKNOWN 态，重投只经 RETRY_WAIT→READY 一条边
        assertThat(com.objwww.pr.control.alert.domain.model.PublicationState.values())
                .containsExactly(com.objwww.pr.control.alert.domain.model.PublicationState.PENDING,
                        com.objwww.pr.control.alert.domain.model.PublicationState.READY,
                        com.objwww.pr.control.alert.domain.model.PublicationState.SENT,
                        com.objwww.pr.control.alert.domain.model.PublicationState.RETRY_WAIT,
                        com.objwww.pr.control.alert.domain.model.PublicationState.DEAD,
                        com.objwww.pr.control.alert.domain.model.PublicationState.SUPPRESSED);
    }

    // ---------------- G0-05/06/07 显式非法迁移锚点（接线验收） ----------------

    @Test
    void illegalTransitionsRejectedAtExplicitCallSites() {
        // Inbox：终态 PROCESSED 不得回流 PROCESSING（G0-05 验收点名用例）
        assertThatThrownBy(() -> InboxStateMachine.requireTransition(
                com.objwww.pr.control.alert.domain.model.InboxState.PROCESSED,
                com.objwww.pr.control.alert.domain.model.InboxState.PROCESSING))
                .isInstanceOf(IllegalTransitionException.class);

        // Run：终态 SUCCEEDED 不得回跑（G0-06）
        assertThatThrownBy(() -> RcaRunStateMachine.requireTransition(
                com.objwww.pr.control.alert.domain.model.RcaRunState.SUCCEEDED,
                com.objwww.pr.control.alert.domain.model.RcaRunState.RUNNING))
                .isInstanceOf(IllegalTransitionException.class);

        // Task：READY 不得跳过 LEASED 直接 DONE（未领取不得完成，G0-07）
        assertThatThrownBy(() -> RcaTaskStateMachine.requireTransition(
                com.objwww.pr.control.alert.domain.model.RcaTaskState.READY,
                com.objwww.pr.control.alert.domain.model.RcaTaskState.DONE))
                .isInstanceOf(IllegalTransitionException.class);
    }
}

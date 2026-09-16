package com.objwww.pr.control.alert.domain.mutation;

import com.objwww.pr.shared.IllegalTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operation 状态机穷举单测（PB-B1，设计基线 §2.11/§2.9）：合法边全集、越边拒绝、
 * 终态无出边、UNKNOWN 必须经 RECONCILING（中间态显式穿越）、释放矩阵（ESCALATED
 * 终态但不释放锁）。
 */
class OperationStateMachineTest {

    @Test
    void opM01_合法边全集_逐条通过() {
        record Edge(OperationStatus from, OperationStatus to) {
        }
        Set<Edge> legal = Set.of(
                new Edge(OperationStatus.PREPARED, OperationStatus.DISPATCHED),
                new Edge(OperationStatus.PREPARED, OperationStatus.CANCELLED_BEFORE_DISPATCH),
                new Edge(OperationStatus.DISPATCHED, OperationStatus.ACKNOWLEDGED),
                new Edge(OperationStatus.DISPATCHED, OperationStatus.UNKNOWN),
                new Edge(OperationStatus.ACKNOWLEDGED, OperationStatus.VERIFIED),
                new Edge(OperationStatus.ACKNOWLEDGED, OperationStatus.UNKNOWN),
                new Edge(OperationStatus.UNKNOWN, OperationStatus.RECONCILING),
                new Edge(OperationStatus.RECONCILING, OperationStatus.VERIFIED),
                new Edge(OperationStatus.RECONCILING, OperationStatus.RETRYABLE),
                new Edge(OperationStatus.RECONCILING, OperationStatus.ESCALATED),
                new Edge(OperationStatus.RECONCILING, OperationStatus.FAILED_CONFIRMED),
                new Edge(OperationStatus.RETRYABLE, OperationStatus.DISPATCHED),
                new Edge(OperationStatus.VERIFIED, OperationStatus.COMPLETED),
                // PE-E2：AM8 人工裁决专用边（ESCALATED 锁保持至人工裁决后的两个出口）
                new Edge(OperationStatus.ESCALATED, OperationStatus.COMPLETED),
                new Edge(OperationStatus.ESCALATED, OperationStatus.FAILED_CONFIRMED));
        for (Edge edge : legal) {
            assertThat(OperationStateMachine.canTransition(edge.from(), edge.to()))
                    .as("合法边 %s → %s", edge.from(), edge.to())
                    .isTrue();
            assertThatCode(() -> OperationStateMachine
                    .checkTransition(edge.from(), edge.to()))
                    .as("合法边 %s → %s 不抛", edge.from(), edge.to())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void opM02_越边与终态复活_全拒() {
        // 跳越中间态（UNKNOWK 直达任何裁决态 = 禁止）+ 终态复活 + 倒序回退
        assertThatThrownBy(() -> OperationStateMachine.checkTransition(
                OperationStatus.UNKNOWN, OperationStatus.VERIFIED))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> OperationStateMachine.checkTransition(
                OperationStatus.UNKNOWN, OperationStatus.RETRYABLE))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> OperationStateMachine.checkTransition(
                OperationStatus.PREPARED, OperationStatus.COMPLETED))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> OperationStateMachine.checkTransition(
                OperationStatus.DISPATCHED, OperationStatus.CANCELLED_BEFORE_DISPATCH))
                .isInstanceOf(IllegalTransitionException.class);
        for (OperationStatus terminal : OperationStatus.values()) {
            if (!terminal.isTerminal() || terminal == OperationStatus.ESCALATED) {
                // ESCALATED 保留人工裁决出边（PE-E2：COMPLETED/FAILED_CONFIRMED 专用边），
                // 不在"零出边"断言范围
                continue;
            }
            for (OperationStatus any : OperationStatus.values()) {
                assertThat(OperationStateMachine.canTransition(terminal, any))
                        .as("终态 %s 无出边（含自环）→ %s", terminal, any)
                        .isFalse();
            }
        }
    }

    @Test
    void opM03_锁释放矩阵_ESCALATED终态但锁保持() {
        // 释放集（§2.9）：VERIFIED/COMPLETED/FAILED_CONFIRMED/CANCELLED_BEFORE_DISPATCH
        assertThat(OperationStatus.VERIFIED.releasesResourceLock()).isTrue();
        assertThat(OperationStatus.COMPLETED.releasesResourceLock()).isTrue();
        assertThat(OperationStatus.FAILED_CONFIRMED.releasesResourceLock()).isTrue();
        assertThat(OperationStatus.CANCELLED_BEFORE_DISPATCH.releasesResourceLock()).isTrue();
        // BUSY 集：UNKNOWN/RECONCILING 不让渡（B 组不变量：无 side-effect zombie），
        // ESCALATED 终态但锁保持至人工裁决
        assertThat(OperationStatus.ESCALATED.isTerminal()).isTrue();
        assertThat(OperationStatus.ESCALATED.releasesResourceLock()).isFalse();
        assertThat(OperationStatus.ESCALATED.holdsResourceLock()).isTrue();
        for (OperationStatus busy : new OperationStatus[] {
                OperationStatus.PREPARED, OperationStatus.DISPATCHED,
                OperationStatus.ACKNOWLEDGED, OperationStatus.UNKNOWN,
                OperationStatus.RECONCILING, OperationStatus.RETRYABLE}) {
            assertThat(busy.isTerminal()).isFalse();
            assertThat(busy.holdsResourceLock()).as("%s 应持锁", busy).isTrue();
        }
    }

    @Test
    void opM04_RcaOperation_状态推进携带时点_越边抛() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        RcaOperation op = RcaOperation.prepare(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "scale.service",
                "a".repeat(64), "res://prod/payment-api", 3, "{}", now);
        assertThat(op.status()).isEqualTo(OperationStatus.PREPARED);
        assertThat(op.dryRun()).isTrue();

        RcaOperation dispatched = op.withStatus(OperationStatus.DISPATCHED, now.plusSeconds(1));
        assertThat(dispatched.dispatchedAt()).isEqualTo(now.plusSeconds(1));
        RcaOperation unknown = dispatched.withStatus(OperationStatus.UNKNOWN, now.plusSeconds(2));
        RcaOperation reconciling = unknown.withStatus(OperationStatus.RECONCILING,
                now.plusSeconds(3));
        RcaOperation verified = reconciling.withStatus(OperationStatus.VERIFIED,
                now.plusSeconds(4));
        assertThat(verified.verifiedAt()).isEqualTo(now.plusSeconds(4));
        RcaOperation completed = verified.withStatus(OperationStatus.COMPLETED,
                now.plusSeconds(5));
        assertThat(completed.completedAt()).isEqualTo(now.plusSeconds(5));

        // 终态复活与越边在域对象上同样抛
        assertThatThrownBy(() -> completed.withStatus(OperationStatus.DISPATCHED, now))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> dispatched.withStatus(OperationStatus.VERIFIED, now))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void opM05_A10域闸退役_真执行铸造仅在prepareReal_PD_D1() {
        // Phase D 解锁日（V121）：dry_run=false 构造不再抛（A10 域闸退役）——
        // 范围纪律移交 mutation_unlock_registry 三元匹配 + 人工审批前置
        RcaOperation real = RcaOperation.prepareReal(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "chaos.resolve", "a".repeat(64),
                "res://demo/checkout", 1, "{}", Instant.now());
        assertThat(real.dryRun()).isFalse();
        assertThat(real.status()).isEqualTo(OperationStatus.PREPARED);
        // dry-run 铸造面保持不变
        RcaOperation dry = RcaOperation.prepare(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "scale.service", "b".repeat(64),
                "res://demo/checkout", 1, "{}", Instant.now());
        assertThat(dry.dryRun()).isTrue();
    }
}

package com.objwww.pr.arena.application;

import com.objwww.pr.arena.domain.model.CompensationEvent;
import com.objwww.pr.arena.domain.model.OutboxState;
import com.objwww.pr.arena.domain.model.ResourceLedgerEntry;
import com.objwww.pr.arena.domain.repository.CompensationOutboxRepository;
import com.objwww.pr.arena.domain.repository.ResourceLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 补偿 worker（M2-13）：领取（SKIP LOCKED + 租约 + epoch）→ 按台账严格逆序回补 →
 * 幂等消费（REFUND 唯一锚冲突 = 已补，跳过）→ 终态。
 * 反向顺序：reversedPlan()（后扣的先补）；回补行引用 DEDUCT 行序号（幂等锚）。
 *
 * <p>失败语义（C-4：修复动作幂等、失败可重试、受 lease epoch 栅栏）：
 * <ul>
 *   <li>毒事件（引用了不存在的 DEDUCT 行）→ 立即 DEAD（非重试面）；</li>
 *   <li>瞬态 SQL 异常 → RETRY_WAIT 退避，attempt 耗尽 → DEAD；</li>
 *   <li>崩溃 → 租约过期回收重领，重投幂等。</li>
 * </ul>
 */
public class CompensationWorker {

    private static final Logger log = LoggerFactory.getLogger(CompensationWorker.class);

    private final CompensationOutboxRepository outbox;
    private final ResourceLedgerRepository ledger;

    public CompensationWorker(CompensationOutboxRepository outbox, ResourceLedgerRepository ledger) {
        this.outbox = outbox;
        this.ledger = ledger;
    }

    /**
     * 单次处理：有可领行则处理一行并返回事件 id，否则返回 empty。
     * 周期循环由装配层驱动（虚拟线程，poll-interval-ms），本方法保持同步确定性。
     */
    public UUID processOne(String owner, Duration lease, Duration backoff) {
        // 顺手回收租约过期的在途行（崩溃恢复面；行数 > 0 时本步照常领取）
        outbox.reapExpiredLeases();

        var claimed = outbox.claimNext(owner, lease);
        if (claimed.isEmpty()) {
            return null;
        }
        var c = claimed.get();
        CompensationEvent event = c.event();
        long epoch = c.leaseEpoch();

        if (!outbox.casState(event.id(), OutboxState.CLAIMED, OutboxState.EXECUTING, epoch)) {
            return event.orderId(); // 被并发回收，让下一轮处理
        }
        long executingEpoch = epoch + 1;
        try {
            boolean compensatedAll = reverseCompensate(event);
            if (compensatedAll) {
                boolean ok = outbox.casTerminal(event.id(), OutboxState.SUCCEEDED, executingEpoch);
                log.debug("补偿完成: order={} result={}", event.orderId(), ok);
            } else {
                // 全部条目已被回补（幂等重投）→ SKIPPED 终态
                boolean ok = outbox.casTerminal(event.id(), OutboxState.SKIPPED, executingEpoch);
                log.debug("补偿幂等跳过: order={} result={}", event.orderId(), ok);
            }
            return event.orderId();
        } catch (PoisonEventException e) {
            log.warn("补偿毒事件进入 DEAD 终态: order={} detail={}", event.orderId(), e.getMessage());
            outbox.casTerminal(event.id(), OutboxState.DEAD, executingEpoch);
            return event.orderId();
        } catch (DataAccessException e) {
            // 瞬态失败：退避重试 / 耗尽 DEAD（epoch 栅栏：只有 EXECUTING 持有者能走）
            boolean fenced = outbox.casRetry(event.id(), executingEpoch, backoff, Integer.MAX_VALUE);
            log.info("补偿失败退避: order={} fenced={} err={}", event.orderId(), fenced,
                    e.getMostSpecificCause().getMessage());
            return event.orderId();
        }
    }

    /**
     * 反向回补：逆序遍历计划；存在 REFUND 记录的条目跳过（幂等）。
     * 返回 false = 全部条目早已回补（重投面）；任一新回补 = true。
     */
    private boolean reverseCompensate(CompensationEvent event) {
        List<CompensationEvent.PlanEntry> reversed = event.reversedPlan();
        boolean anyCompensated = false;
        for (CompensationEvent.PlanEntry entry : reversed) {
            // 毒事件：引用的 DEDUCT 行不存在（计划与台账不一致，人工介入面）
            ResourceLedgerEntry deduct = ledger.listDeductions(event.orderId()).stream()
                    .filter(d -> d.resourceType() == entry.resourceType()
                            && d.deductionSeq() == entry.deductionSeq())
                    .findFirst()
                    .orElseThrow(() -> new PoisonEventException(
                            "DEDUCT 行缺失: " + entry.resourceType() + "#" + entry.deductionSeq()));
            if (ledger.hasRefund(event.orderId(), deduct.resourceType().name(),
                    deduct.deductionSeq())) {
                continue;
            }
            boolean inserted = ledger.insertRefundIfAbsent(deduct.asRefund());
            anyCompensated = anyCompensated || inserted;
        }
        return anyCompensated;
    }

    private static final class PoisonEventException extends IllegalStateException {
        PoisonEventException(String message) {
            super(message);
        }
    }
}

package com.objwww.pr.arena.application;

import com.objwww.pr.arena.application.chaos.FaultGate;
import com.objwww.pr.arena.application.chaos.FaultType;
import com.objwww.pr.arena.infrastructure.persistence.PostgresFulfillmentAttemptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 履约消费者模拟（M-a C2b 的 F15/S22 承载面）：
 * 扫描 CONFIRMED 且尚无尝试行的履约单，产一次消费尝试（正常恰一次）；
 * F15 会话命中该 chaos- correlation 时重复尝试（ack 失败 → at-least-once 幂等缺失），
 * 重复行由 DomainProbe 的 oa_duplicate_fulfillments_current 检出（INV-AM2-5：本类不自报）。
 */
public class FulfillmentAttemptSimulator {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentAttemptSimulator.class);

    private final PostgresFulfillmentAttemptStore store;
    private final FaultGate faultGate;

    public FulfillmentAttemptSimulator(PostgresFulfillmentAttemptStore store,
                                       FaultGate faultGate) {
        this.store = store;
        this.faultGate = faultGate;
    }

    /** @return 本轮消费尝试行数 */
    public int consumeOnce() {
        int rows = 0;
        for (PostgresFulfillmentAttemptStore.Candidate c : store.confirmedWithoutAttempt()) {
            boolean f15Duplicate = c.correlationId() != null
                    && c.correlationId().startsWith("chaos-")
                    && faultGate.active(FaultType.F15, c.correlationId());
            rows++;
            store.insertAttempt(UUID.randomUUID(), c.fulfillmentId(), "fulfillment-simulator");
            if (f15Duplicate) {
                log.warn("F15 重复消费：ack 失败重投，同履约单二次尝试: fulfillment={}",
                        c.fulfillmentId());
                rows++;
                store.insertAttempt(UUID.randomUUID(), c.fulfillmentId(), "fulfillment-simulator");
            }
        }
        return rows;
    }
}

package com.objwww.pr.control.alert.domain.event;

import java.util.Objects;
import java.util.UUID;

/**
 * 统一事件账本端口（AM4 M4-10/11，V14 rca_event）。实现方在"锁 run 行 → seq+1 →
 * 插入"的同一短事务内完成分配与落账：同 run 并发追加串行化，seq 连续单调无倒退。
 *
 * <p>两种提交语义（M4-11）：
 * <ul>
 *   <li>{@link #append} —— <b>状态事实</b>：加入调用方当前事务（无事务则自起短事务），
 *       状态回滚则事件一并回滚；</li>
 *   <li>{@link #appendIndependent} —— <b>进度事件</b>：独立短事务（REQUIRES_NEW），
 *       调用方回滚不影响已提交事件。</li>
 * </ul>
 *
 * <p>幂等/冲突（M4-10）：同 (run_id, event_id) 且 payload digest 相同的重放 = 幂等，
 * 返回既有 seq；digest 不同 = 冲突显式报错（禁静默 no-op）。返回值为本事件分到的
 * per-run seq（SSE/回放游标）。append-only：实现不得提供改/删。
 */
public interface RcaEventAppender {

    /** 状态事实事件：同事务写（join 调用方事务） */
    long append(UUID runId, EventDraft draft);

    /** 进度事件：独立短事务（REQUIRES_NEW），调用方回滚不影响 */
    long appendIndependent(UUID runId, EventDraft draft);

    /** 事件草稿：payload 为调用方 canonical 序列化后的 JSON 串（digest 由实现方计算） */
    record EventDraft(UUID eventId, String eventType, String payloadJson) {
        public EventDraft {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }
}

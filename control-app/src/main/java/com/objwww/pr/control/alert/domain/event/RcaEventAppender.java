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

    /**
     * PA-A2 哈希链验链（V112）：同 run 全链重算比对。NULL 哈希行（V112 前的 legacy
     * 事件）视为链段边界跳过校验——新段从 NULL 尾后首行起算 GENESIS。
     * 声明口径：tamper-evident <b>under the assumed DB write boundary</b>
     * （评审 R9：同库管理员可整链重算；外部 WORM 锚另立增量）。
     */
    default ChainReport verifyChain(UUID runId) {
        throw new UnsupportedOperationException("verifyChain 仅支持哈希链写的实现");
    }

    /** 验链报告：brokenAtSeq=-1 = 全链通过；verified = 实际参与哈希校验的行数 */
    record ChainReport(UUID runId, long events, long verified, long brokenAtSeq) {
        public boolean ok() {
            return brokenAtSeq < 0;
        }
    }

    /** 有事件的 run 全集（验链作业驱动面；实现方可分页，作业侧逐 run 独立推进） */
    default java.util.List<UUID> runIdsWithEvents() {
        throw new UnsupportedOperationException("runIdsWithEvents 仅支持哈希链写的实现");
    }

    /** 事件草稿：payload 为调用方 canonical 序列化后的 JSON 串（digest 由实现方计算） */
    record EventDraft(UUID eventId, String eventType, String payloadJson) {
        public EventDraft {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(payloadJson, "payloadJson");
        }
    }
}

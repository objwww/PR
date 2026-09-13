package com.objwww.pr.control.alert.application.tool;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * WC-3（§5.2）在途工具调用的进程内取消通知 + RV03 在飞事实生命周期：Run 落终态
 * （CANCEL/APPLY 或看门狗过期）后置通知 {@link #cancelRun} 中断该 Run 名下在途
 * 工具等待。
 *
 * <p>定位只是<b>加速</b>——收敛骨架是持久事实（Run 终态行）+ 执行边界周期探针
 * （{@code ExecutionControl.controlSignal}）+ 工具 deadline 最小化；本通知丢了
 * 顶多多等到探针/超时，不破坏正确性。单进程语义：其他实例的工具等待由各自的
 * 探针与 deadline 兜底，不引消息中间件；对外部系统实际工作是否结束只能按协议
 * 确认，本地线程退出不是远端完成证明。
 *
 * <p><b>RV03 句柄状态机（区分「停止请求/等待结束/执行退出」）</b>：
 * <ul>
 *   <li>QUEUED→RUNNING：执行包装器入口 CAS（取消与启动竞争，取消胜=
 *       CANCELLED_BEFORE_START，执行次数为 0）；</li>
 *   <li>STOP_REQUESTED：与相位正交的停止标记；</li>
 *   <li>EXITED：<b>执行包装器 finally</b> 才置位——执行体忽略中断时在飞事实保留
 *       （inflightCount 不假报归零），调用方 finally 只结束「等待」，不冒充执行退出；
 *       {@code FutureTask.done()} 在取消时可早于 callable 退出，不作为执行退出回调；</li>
 *   <li>注册/删除全部经按 Run 的原子 {@code compute}（杜绝 computeIfAbsent→add 与
 *       empty→remove 交错把新成员加进已移除的 Set）；入池拒绝也走回收路径。</li>
 * </ul>
 * 取消墓碑（stopCancelled）：本类不自回收——Run 终态且全部静默后由持有全局知识的
 * 调用方 {@link #release}（新动作仍被持久终态检查拒绝，墓碑只是加速面的记忆）；
 * 生命周期=进程内，按 Run 有界。
 */
public final class InFlightToolCancels {

    /** 单次调用的执行句柄（相位 + 停止标记 + 中断面） */
    public static final class Handle {
        enum Phase {QUEUED, RUNNING, EXITED, CANCELLED_BEFORE_START}

        private final UUID runId;
        private Phase phase = Phase.QUEUED;
        private boolean stopRequested;
        private volatile Future<?> future;

        private Handle(UUID runId) {
            this.runId = runId;
        }

        /** 执行包装器入口：QUEUED→RUNNING；已请求停止 → CANCELLED_BEFORE_START（不启动） */
        synchronized boolean beginRun() {
            if (stopRequested) {
                phase = Phase.CANCELLED_BEFORE_START;
                return false;
            }
            phase = Phase.RUNNING;
            return true;
        }

        /** 取消侧：置停止标记（与 beginRun 同锁竞争；中断在锁外做，不持锁慢操作） */
        synchronized void requestStop() {
            stopRequested = true;
        }

        /** 执行包装器 finally：实际退出事实（volatile 单写） */
        void markExit() {
            phase = Phase.EXITED;
        }

        /** 中断面挂接（调用方在 submit 后把 Future 交给句柄；cancel(true) 只请求中断） */
        public void attach(Future<?> f) {
            this.future = f;
        }

        /** 中断面：请求底层 Future 中断（cancel(true) 只请求，不证明退出）。
         * 已完成的 CompletableFuture 式 future 会同步抛 CancellationException——
         * 中断是尽力通知，异常吞掉不打断取消扇出 */
        void interrupt() {
            Future<?> f = future;
            if (f != null) {
                try {
                    f.cancel(true);
                } catch (CancellationException alreadyDone) {
                    // future 已终局：无需中断
                }
            }
        }

        /** 在飞事实：未退出（QUEUED/RUNNING/CANCELLED_BEFORE_START 未清前不算已执行） */
        boolean inFlight() {
            return phase == Phase.QUEUED || phase == Phase.RUNNING;
        }

        boolean finished() {
            return phase == Phase.EXITED || phase == Phase.CANCELLED_BEFORE_START;
        }

        UUID runId() {
            return runId;
        }
    }

    private final Map<UUID, Set<Handle>> byRun = new ConcurrentHashMap<>();
    private final Set<UUID> stopCancelled = ConcurrentHashMap.newKeySet();

    /**
     * 等待开始前注册句柄（按 Run 原子 compute：成员增删与键回收同锁完成）。
     * 返回句柄随执行包装器进入 beginRun/markExit 生命周期。
     */
    public Handle register(UUID runId) {
        Handle handle = new Handle(runId);
        byRun.compute(runId, (k, set) -> {
            Set<Handle> s = set == null ? ConcurrentHashMap.newKeySet() : set;
            s.add(handle);
            return s;
        });
        if (stopCancelled.contains(runId)) {
            // 粘性取消：cancelRun 落在 submit 与 register 之间的小窗（调度让位）时补
            // 通知——通知面只做加速，正确性骨架（终态行+探针+期限）不变
            handle.requestStop();
        }
        return handle;
    }

    /**
     * 调用方等待结束（finally）：只结束「等待」——句柄已实际退出/未启动才回收；
     * 执行体忽略中断仍在运行时，在飞事实保留（EXITED 由执行包装器 finally 置位
     * 后经 {@link #exit} 回收）。
     */
    public void endWait(UUID runId, Handle handle) {
        if (handle.finished()) {
            remove(runId, handle);
        }
    }

    /** 执行包装器 finally：置实际退出事实并回收句柄（幂等） */
    public void exit(Handle handle) {
        handle.markExit();
        remove(handle.runId(), handle);
    }

    /** 入池拒绝/排队取消等一次性回收（句柄未启动即终局） */
    public void reclaim(UUID runId, Handle handle) {
        handle.markExit();
        remove(runId, handle);
    }

    private void remove(UUID runId, Handle handle) {
        byRun.compute(runId, (k, set) -> {
            if (set == null) {
                return null;
            }
            set.remove(handle);
            return set.isEmpty() ? null : set; // 清空即回收键（原子，杜绝交错丢员）
        });
    }

    /**
     * Run 取消后置通知：标记 + 请求停止 + 中断该 Run 名下全部在途工具等待。
     * 条目不在此清——执行包装器 finally（exit）与调用方 finally（endWait）才是
     * 移除面；取消到真正静默的窗口里 inflightCount 保持真值（读面「还有 N 个调用
     * 停止中」的口径）。持锁面只做标记与快照，中断在锁外。
     */
    public void cancelRun(UUID runId) {
        stopCancelled.add(runId);
        byRun.compute(runId, (k, set) -> {
            if (set != null) {
                set.forEach(Handle::requestStop);
            }
            return set;
        });
        Set<Handle> set = byRun.get(runId);
        if (set != null) {
            set.forEach(Handle::interrupt);
        }
    }

    /** 本 Run 是否因停止而被取消（CancellationException 归因依据） */
    public boolean wasStopCancelled(UUID runId) {
        return stopCancelled.contains(runId);
    }

    /**
     * 本 Run 当前在飞事实数（WC-5 读面 inflightCount；单进程视角）：QUEUED/RUNNING
     * 计入——执行体忽略中断未退出时不假报归零（RV03/T12）。
     */
    public int inflightCount(UUID runId) {
        Set<Handle> set = byRun.get(runId);
        if (set == null) {
            return 0;
        }
        int n = 0;
        for (Handle h : set) {
            if (h.inFlight()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 墓碑回收（RV03/T15）：Run 终态且注册/排队/执行全部结束后由持有全局知识的
     * 调用方释放；新动作仍被持久 Run 终态检查拒绝，本标记只是加速面记忆。不做
     * TTL——TTL 后允许旧 Run 重注册会破坏粘性取消语义。
     */
    public void release(UUID runId) {
        stopCancelled.remove(runId);
    }
}

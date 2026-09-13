package com.objwww.pr.control.alert.application.tool;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * WC-3（§5.2）在途工具调用的进程内取消通知：Run 落终态（CANCEL/APPLY 或看门狗
 * 过期）后置通知 {@link #cancelRun} 中断该 Run 名下在途工具等待。
 *
 * <p>定位只是<b>加速</b>——收敛骨架是持久事实（Run 终态行）+ 执行边界周期探针
 * （{@code ExecutionControl.controlSignal}）+ 工具 deadline 最小化；本通知丢了
 * 顶多多等到探针/超时，不破坏正确性。单进程语义：其他实例的工具等待由各自的
 * 探针与 deadline 兜底，不引消息中间件。
 *
 * <p>无锁线程安全：register/unregister 由调用线程配对；cancelRun 由通知线程发起。
 * stopCancelled 标记用于把 {@code Future.cancel(true)} 引发的 CancellationException
 * 与线程池 shutdown 等其他取消区分开——前者转 {@code StoppedException}（终态失败，
 * 不触发工具重试），后者原样上抛。
 */
public final class InFlightToolCancels {

    private final Map<UUID, Set<Future<?>>> byRun = new ConcurrentHashMap<>();
    private final Set<UUID> stopCancelled = ConcurrentHashMap.newKeySet();

    public void register(UUID runId, Future<?> future) {
        Set<Future<?>> fs = byRun.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet());
        fs.add(future);
        if (stopCancelled.contains(runId)) {
            // 粘性取消：cancelRun 落在 submit 与 register 之间的小窗（调度让位）时补
            // 通知——通知面只做加速，正确性骨架（终态行+探针+期限）不变
            future.cancel(true);
        }
    }

    public void unregister(UUID runId, Future<?> future) {
        Set<Future<?>> fs = byRun.get(runId);
        if (fs != null) {
            fs.remove(future);
            // 清空即回收键（与 cancelRun 不清空条目配套：静默后不留空集垃圾）
            if (fs.isEmpty()) {
                byRun.remove(runId, fs);
            }
        }
    }

    /**
     * Run 取消后置通知：标记 + 中断该 Run 名下全部在途工具等待。条目不在此清
     * ——各调用线程的 finally unregister 才是移除面；cancelRun 到真正静默的窗口
     * 里 inflightCount 保持真值（读面"还有 N 个调用停止中"的口径）。
     */
    public void cancelRun(UUID runId) {
        stopCancelled.add(runId);
        Set<Future<?>> fs = byRun.get(runId);
        if (fs != null) {
            fs.forEach(f -> f.cancel(true));
        }
    }

    /** 本 Run 是否因停止而被取消（CancellationException 归因依据） */
    public boolean wasStopCancelled(UUID runId) {
        return stopCancelled.contains(runId);
    }

    /** 本 Run 当前在途工具调用数（WC-5 读面 inflightCount；单进程视角） */
    public int inflightCount(UUID runId) {
        Set<Future<?>> fs = byRun.get(runId);
        return fs == null ? 0 : fs.size();
    }
}

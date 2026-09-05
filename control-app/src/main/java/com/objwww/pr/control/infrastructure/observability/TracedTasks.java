package com.objwww.pr.control.infrastructure.observability;

import io.micrometer.tracing.Tracer;

import java.util.Objects;

/**
 * 异步边界 span 关联（M3-29 最小链路）：虚拟线程/自建线程不携带 OTel context，
 * 提交点显式捕获当前 span、执行点用 {@code withSpan} 恢复——trace 在线程跳变处
 * 不断链。生产接线点（inbox/worker 循环）待 OTel collector 上线后接，本类先以
 * 关联测试钉死机制（无 exporter 时 span 仅内存态，接线零风险）。
 */
public final class TracedTasks {

    private TracedTasks() {
    }

    /** 包装 runnable：提交时刻的 currentSpan 在执行线程内恢复为 current */
    public static Runnable wrap(Tracer tracer, Runnable task) {
        Objects.requireNonNull(task);
        if (tracer == null) {
            return task;   // 无 tracing 装配 = 直通（诚实降级，不造 no-op span）
        }
        var span = tracer.currentSpan();
        return () -> {
            // SpanInScope 即"withSpan 期间 span 为 current"，close 恢复——跨线程恢复 current
            try (Tracer.SpanInScope scope = span == null ? null : tracer.withSpan(span)) {
                task.run();
            }
        };
    }
}

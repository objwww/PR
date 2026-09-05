package com.objwww.pr.control.infrastructure.observability;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-29：异步边界 span 关联——提交点捕获的 currentSpan 在执行线程内恢复为
 * current（traceId 相等）；无 tracer 时直通。brave 内存桥（无 reporter）即够，
 * 无 exporter 时 span 仅内存态。
 */
class TracedTasksTest {

    private Tracing tracing;

    private Tracer tracer() {
        brave.propagation.CurrentTraceContext ctx =
                ThreadLocalCurrentTraceContext.newBuilder().build();
        tracing = Tracing.newBuilder().currentTraceContext(ctx).build();
        return new BraveTracer(tracing.tracer(),
                new BraveCurrentTraceContext(ctx), new BraveBaggageManager());
    }

    @AfterEach
    void tearDown() {
        if (tracing != null) {
            tracing.close();
        }
    }

    @Test
    @DisplayName("跨线程 traceId 关联：wrap 后子线程 currentSpan 与提交点同 trace")
    void spanSurvivesThreadBoundary() throws Exception {
        Tracer tracer = tracer();
        Span parent = tracer.nextSpan().name("parent").start();

        AtomicReference<String> childTraceId = new AtomicReference<>();
        Runnable wrapped;
        try (Tracer.SpanInScope scope = tracer.withSpan(parent)) {
            wrapped = TracedTasks.wrap(tracer, () -> {
                Span current = tracer.currentSpan();
                childTraceId.set(current == null ? null : current.context().traceId());
            });
        }

        Thread.ofVirtual().start(wrapped).join();
        parent.end();

        assertThat(childTraceId.get()).isNotNull();
        assertThat(childTraceId.get()).isEqualTo(parent.context().traceId());
    }

    @Test
    @DisplayName("无 currentSpan 时提交：子线程 currentSpan 为空但不抛")
    void noCurrentSpanStillRuns() throws Exception {
        Tracer tracer = tracer();

        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        Runnable wrapped = TracedTasks.wrap(tracer, () -> ran.set(true));
        Thread.ofVirtual().start(wrapped).join();

        assertThat(ran.get()).isTrue();
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    @DisplayName("tracer 为 null：直通返回原 runnable（无 tracing 装配=诚实降级）")
    void nullTracerPassesThrough() {
        Runnable task = () -> {
        };
        assertThat(TracedTasks.wrap(null, task)).isSameAs(task);
    }
}

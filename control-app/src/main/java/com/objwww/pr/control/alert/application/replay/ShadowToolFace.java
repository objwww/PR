package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 影子在线只读工具面（AM4 M4-35）：Online Read Shadow 的工具出口，与生产面
 * 物理隔离的三道闸——① <b>仅 R0/R1</b>：从生产注册面裁剪出影子注册面（R2/R3
 * 物理不进入，调用即 UNKNOWN_TOOL；裁剪后为空 = 启动期硬失败）；② <b>REDTEAM
 * 物理禁入</b>：构造期发现 redteam 命名空间工具即 fail-fast，调用期第二闸显式
 * POLICY_DENIED（双闸同 M4-16 惯例——清单裁剪可被绕过，此处不可）；③ <b>独立
 * slot/限流</b>：自有调用池（独立并发槽）+ 固定窗口独立配额（耗尽 = 模型可见族
 * RATE_LIMITED，可退避重试，不影响生产面额度）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public final class ShadowToolFace implements ToolInvoker {

    /** REDTEAM 命名空间（影子面物理禁入的工具名前缀） */
    public static final String REDTEAM_NAMESPACE = "redteam";
    private static final String NAMESPACE_SUFFIX = ".";

    private final ToolRegistry readOnlyRegistry;
    private final ToolGateway delegate;
    private final WindowRateLimiter limiter;

    public ShadowToolFace(ToolRegistry productionRegistry, ToolPolicy shadowPolicy,
            ExecutorService shadowPool, long maxCallsPerWindow, long windowMillis,
            Clock clock) {
        Objects.requireNonNull(productionRegistry, "productionRegistry");
        Objects.requireNonNull(shadowPolicy, "shadowPolicy");
        Objects.requireNonNull(shadowPool, "shadowPool");
        Objects.requireNonNull(clock, "clock");
        if (maxCallsPerWindow < 1 || windowMillis < 1) {
            throw new IllegalArgumentException("影子限流配额与窗口必须为正: "
                    + maxCallsPerWindow + "/" + windowMillis);
        }
        List<ToolRegistry.Registration> readOnly = new ArrayList<>();
        for (ToolRegistry.Registration registration : productionRegistry.all()) {
            String name = registration.definition().name();
            if (name.startsWith(REDTEAM_NAMESPACE + NAMESPACE_SUFFIX)) {
                throw new IllegalStateException("影子面 REDTEAM 物理禁入（构造期 fail-fast）: "
                        + name);
            }
            if (registration.definition().risk().executable()) {
                readOnly.add(registration);
            }
        }
        if (readOnly.isEmpty()) {
            throw new IllegalStateException("影子注册面为空（启动期硬失败）："
                    + "生产注册面无 R0/R1 只读工具，影子面拒绝构建");
        }
        this.readOnlyRegistry = new ToolRegistry(readOnly);
        this.delegate = new ToolGateway(this.readOnlyRegistry, shadowPolicy,
                shadowPool, clock, null);
        this.limiter = new WindowRateLimiter(maxCallsPerWindow, windowMillis, clock);
    }

    /** 影子调用：REDTEAM 第二闸 → 独立限流 → 活执行（裁剪后注册面 + 独立 slot） */
    @Override
    public ToolGateway.ToolInvocationResult invoke(ToolGateway.ToolInvocation invocation) {
        if (invocation.toolName().startsWith(REDTEAM_NAMESPACE + NAMESPACE_SUFFIX)) {
            throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                    "POLICY_DENIED: 影子面 REDTEAM 物理禁入: " + invocation.toolName());
        }
        if (!limiter.tryReserve()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                    "影子面独立限流（固定窗口配额耗尽，可退避重试）");
        }
        try {
            return delegate.invoke(invocation);
        } catch (ToolControlPlaneException e) {
            limiter.refund(); // 控制面拒绝（未执行）不耗影子配额
            throw e;
        }
    }

    /** 裁剪后只读注册面（与影子出口同源——Agent 侧校验看见的面 = 影子面可执行的面） */
    public ToolRegistry readOnlyView() {
        return readOnlyRegistry;
    }

    /** 固定窗口限流（独立额度；Clock 注入保测试确定性，非线程争用面） */
    static final class WindowRateLimiter {

        private final long maxCalls;
        private final long windowMillis;
        private final Clock clock;
        private long bucketStart = -1;
        private long used;

        WindowRateLimiter(long maxCalls, long windowMillis, Clock clock) {
            this.maxCalls = maxCalls;
            this.windowMillis = windowMillis;
            this.clock = clock;
        }

        synchronized boolean tryReserve() {
            long bucket = clock.millis() / windowMillis;
            if (bucket != bucketStart) {
                bucketStart = bucket;
                used = 0;
            }
            if (used >= maxCalls) {
                return false;
            }
            used++;
            return true;
        }

        /** 退回一次预留（控制面拒绝未执行的调用不耗配额） */
        synchronized void refund() {
            if (used > 0) {
                used--;
            }
        }
    }
}

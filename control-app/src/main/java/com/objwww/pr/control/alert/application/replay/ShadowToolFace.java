package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

/**
 * 影子面别名（M6-01 起废弃）：实现体已更名 {@link ReadOnlyToolFace}（Native
 * Canary 共用出口，REDTEAM 双闸降为 redteamOnly 策略开关，C-70）。本名保留
 * 双闸姿态（redteamOnly=true 硬编码），使原 UT 不改一字全绿为行为保持验收
 * （M4-28 惯例）；M6-07 引擎接位收尾时随影子面退役移除。
 *
 * @author wanghua
 * @date 2026-09-05
 * @deprecated 用 {@link ReadOnlyToolFace}（redteamOnly 显式传参）
 */
@Deprecated
public final class ShadowToolFace implements ToolInvoker {

    private final ReadOnlyToolFace delegate;

    public ShadowToolFace(ToolRegistry productionRegistry, ToolPolicy shadowPolicy,
            ExecutorService shadowPool, long maxCallsPerWindow, long windowMillis,
            Clock clock) {
        this.delegate = new ReadOnlyToolFace(productionRegistry, shadowPolicy, shadowPool,
                maxCallsPerWindow, windowMillis, clock, true);
    }

    @Override
    public ToolGateway.ToolInvocationResult invoke(ToolGateway.ToolInvocation invocation) {
        return delegate.invoke(invocation);
    }

    public ToolRegistry readOnlyView() {
        return delegate.readOnlyView();
    }
}

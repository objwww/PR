package com.objwww.pr.control.alert.application.mutation;

import com.objwww.pr.control.alert.domain.mutation.RcaOperation;

import java.util.Map;
import java.util.Objects;

/**
 * BA-191 真执行路由（executor 端点按 action_id 路由的扩展点）：注册表放行的每个
 * 动作各有专属执行面（service.rollback → flagd 旗标回滚执行器），通用 HTTP
 * executor endpoint 为可空兜底。无路由且无兜底 = REAL_EXECUTOR_ROUTE_ABSENT
 * （TIMEOUT_UNKNOWN：锁保持 BUSY，reconcile 裁决，不静默丢、不假装执行）。
 */
public class RoutedActionRunner implements ActionRunner {

    private final Map<String, ActionRunner> routes;
    private final ActionRunner fallback; // 可空（通用 executor endpoint 未配置）

    public RoutedActionRunner(Map<String, ActionRunner> routes, ActionRunner fallback) {
        this.routes = Map.copyOf(Objects.requireNonNull(routes, "routes"));
        this.fallback = fallback;
    }

    @Override
    public Outcome run(RcaOperation operation) {
        return runDetailed(operation).outcome();
    }

    @Override
    public Result runDetailed(RcaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        ActionRunner chosen = routes.getOrDefault(operation.actionId(), fallback);
        if (chosen == null) {
            return new Result(Outcome.TIMEOUT_UNKNOWN, "REAL_EXECUTOR_ROUTE_ABSENT: 动作 "
                    + operation.actionId() + " 无真执行路由");
        }
        return chosen.runDetailed(operation);
    }
}

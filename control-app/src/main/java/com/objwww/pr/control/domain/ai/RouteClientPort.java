package com.objwww.pr.control.domain.ai;

import java.time.Duration;

/**
 * 底层端口（§3.1/附录 C）：单次物理调用，至多一次真实 HTTP（I34）。
 *
 * <p>只有 ModelGateway 可依赖（AFT-23）；超时/异常一律映射为 RouteCallOutcome.Failed 返回，
 * 不向 Gateway 抛供应商异常。
 */
public interface RouteClientPort {

    /**
     * 单次物理调用。
     *
     * @param request        模型请求
     * @param perCallTimeout 本次调用硬超时
     * @return 成功或失败 outcome
     */
    RouteCallOutcome complete(ModelRequest request, Duration perCallTimeout);
}

package com.objwww.pr.control.domain.ai;

/**
 * 上层端口（§3.1/附录 C）：ReviewAgentLoop 唯一可见的模型依赖（AFT-19/23）。
 *
 * <p>不暴露 route/熔断/账本概念；抛出异常族：
 * <ul>
 *   <li>{@link ModelBudgetExceededException} - 预算拒绝（既有）</li>
 *   <li>{@link ModelRetryDeferredException} - 需长等待 Defer</li>
 *   <li>{@link ModelCallFailedException} - 终态失败</li>
 * </ul>
 */
public interface ModelGatewayPort {

    /**
     * 完成一次模型调用（含重试/fallback/熔断/账本全链路）。
     *
     * @param request 模型请求
     * @param context 调用上下文（显式传递，禁止 ThreadLocal）
     * @return 带路由信息的结果
     */
    RoutedModelResult complete(ModelRequest request, ModelCallContext context);
}

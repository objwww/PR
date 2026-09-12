package com.objwww.pr.control.drill.application;

import org.springframework.web.client.HttpClientErrorException;

/**
 * DR-03 注入失败三态映射（§7.4「远端 on 超时记 ACTION_UNKNOWN」；arena/flagd
 * 两个 kind 适配器共用）：
 * <ul>
 *   <li>{@link IllegalArgumentException}/{@link IllegalStateException} = 驱动
 *       前置校验拒绝（参数缺失/故障族缺失/CHAOS_ADMIN_TOKEN fail-closed）——
 *       这些路径在任何网络调用之前抛出，确定零副作用 → NOT_PERFORMED；</li>
 *   <li>4xx（{@link HttpClientErrorException}）= 管理面明确拒绝本次请求，
 *       确定零副作用 → NOT_PERFORMED；</li>
 *   <li>其余一切（超时 ResourceAccessException / 5xx / 连接异常 / 台账落账失败等
 *       未知运行时）：请求可能已被管理面应用，无法判定 → UNKNOWN（DrillWorker
 *       必先进 RECOVERING），reason 携带固定身份对账锚。严禁在此假成功，
 *       也严禁把不可判定的失败吞成 NOT_PERFORMED。</li>
 * </ul>
 */
final class DrillInjectionFailures {

    private DrillInjectionFailures() {
    }

    /**
     * @param failure       驱动抛出的异常
     * @param fixedIdentity 固定身份对账锚（arena = 每作业派生的有效实例 id；
     *                      flagd = scenarioId/flag）——ACTION_UNKNOWN 对账不换 id
     */
    static DrillInjectionPort.Outcome from(Throwable failure, String fixedIdentity) {
        if (failure instanceof IllegalArgumentException
                || failure instanceof IllegalStateException) {
            return DrillInjectionPort.Outcome.notPerformed(
                    "前置校验拒绝（确定零副作用，未发出注入请求）: " + failure.getMessage());
        }
        if (failure instanceof HttpClientErrorException clientError) {
            return DrillInjectionPort.Outcome.notPerformed(
                    "管理面明确拒绝 HTTP " + clientError.getStatusCode()
                            + "（确定零副作用）: " + fixedIdentity);
        }
        return DrillInjectionPort.Outcome.unknown(
                "注入结果无法判定（ACTION_UNKNOWN，可能已注入；按固定身份对账，"
                        + "不换新 id 盲目重试）: " + fixedIdentity + " — "
                        + failure.getClass().getSimpleName() + ": "
                        + failure.getMessage());
    }
}

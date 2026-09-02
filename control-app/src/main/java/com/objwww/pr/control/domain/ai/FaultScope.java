package com.objwww.pr.control.domain.ai;

/**
 * 故障域归属（§4.2/§4.3）：决定 fallback 资格的关键维度。
 *
 * <p>域从配置派生（endpoint_scope/quota_scope/credential_domain），
 * 不是手工配置——配置变化时域归属自动变化，fallback 资格矩阵自动生效。
 */
public enum FaultScope {
    /** 模型级（只影响该模型）：模型级 429/模型级 5xx/模型不存在 */
    MODEL,

    /** 端点级（整个 base-url）：超时/DNS/连接断/端点级 5xx/协议错误 */
    ENDPOINT,

    /** 账号级（配额/账单）：配额限流/配额耗尽/欠费/未开通 */
    ACCOUNT,

    /** 凭证级（key/权限）：401/403 权限拒绝 */
    CREDENTIAL
}

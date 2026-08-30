package com.objwww.pr.publisher.infrastructure.credential;

/**
 * 凭证中介端口（§3.2 CredentialBroker，v2.2 E6）。
 * 完整实现（T14）：App 私钥签 JWT → 铸造 installation token（收窄 scope、TTL 内缓存）
 * + 对 Control 的只读 token 签发窄接口。
 * 纪律：私钥不出进程、token 不落库不落日志；凭证缺失/失效必须抛异常（fail-closed，E5）。
 */
public interface CredentialBroker {

    /**
     * 取当前可用的 GitHub 写/读 token（默认 installation——M0 单安装实例，
     * installation 由配置指定；多 installation 是 M7 的事）。
     */
    String token(TokenScope scope);

    /**
     * 为指定 installation 铸造/复用收窄 scope 的 token（E6 缓存键 = (installation_id, scope)）。
     * 实现必须在凭证缺失/失效时抛异常（fail-closed，E5），不得返回空串。
     */
    String token(long installationId, TokenScope scope);
}

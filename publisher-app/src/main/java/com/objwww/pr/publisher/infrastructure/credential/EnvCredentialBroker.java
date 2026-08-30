package com.objwww.pr.publisher.infrastructure.credential;

import java.util.Objects;

/**
 * CredentialBroker 环境变量 stub：从 {@code GITHUB_WRITE_TOKEN} 读取，不分 scope/installation
 * （本地手动联调 fallback）。缺失时 fail-closed 抛异常（E5）。
 * 正式路径是 GitHubAppCredentialBroker（App JWT → installation token，T14）。
 */
public class EnvCredentialBroker implements CredentialBroker {

    public static final String ENV_NAME = "GITHUB_WRITE_TOKEN";

    @Override
    public String token(TokenScope scope) {
        return token(0L, scope);
    }

    @Override
    public String token(long installationId, TokenScope scope) {
        Objects.requireNonNull(scope, "scope");
        String token = System.getenv(ENV_NAME);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("环境变量 " + ENV_NAME + " 未配置（fail-closed，E5）");
        }
        return token;
    }
}

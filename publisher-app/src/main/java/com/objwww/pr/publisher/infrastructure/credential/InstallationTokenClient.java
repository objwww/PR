package com.objwww.pr.publisher.infrastructure.credential;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * installation token 铸造端口（T14）：POST /app/installations/{id}/access_tokens。
 * 独立成接口是为了 broker 缓存语义（UT-08）可用假实现单测，不触网。
 */
public interface InstallationTokenClient {

    /**
     * 铸造收窄 scope 的 installation token。
     *
     * @param appJwt       App JWT（Bearer）
     * @param repositories 收窄到的仓库名列表（空 = 不收窄，installation 全域）
     */
    MintedToken mint(long installationId, TokenScope scope, List<String> repositories, String appJwt);

    /** 铸造产出：token + GitHub 侧过期时刻（缓存 TTL 依据） */
    record MintedToken(String token, Instant expiresAt) {
        public MintedToken {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}

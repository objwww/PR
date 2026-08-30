package com.objwww.pr.publisher.infrastructure.credential;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CredentialBroker 完整实现（T14，v2.2 E6）：App 私钥签 JWT → 铸造 installation token
 * （铸造时收窄 repository + permission scope）→ TTL 内缓存。
 *
 * <p>缓存语义（UT-08）：键 = (installation_id, scope)；命中条件 =
 * expires_at - now &gt; 5 分钟刷新余量（过期前 5 分钟即重铸，不给在途请求留临期 token）。
 * token 只存内存（进程重启即清空重铸），不落库不落日志。
 *
 * <p>写路径（{@link #token(TokenScope)}）走配置的默认 installation（M0 单安装实例）；
 * 窄接口路径（{@link #token(long, TokenScope)}）按请求 installation 铸造。
 */
public class GitHubAppCredentialBroker implements CredentialBroker {

    /** 过期前刷新余量（E6：5 分钟） */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private record CacheKey(long installationId, TokenScope scope) {
    }

    private final AppJwtFactory jwtFactory;
    private final InstallationTokenClient tokenClient;
    private final long defaultInstallationId;
    private final List<String> mintRepositories;
    private final Clock clock;

    private final ConcurrentHashMap<CacheKey, InstallationTokenClient.MintedToken> cache =
            new ConcurrentHashMap<>();

    public GitHubAppCredentialBroker(AppJwtFactory jwtFactory, InstallationTokenClient tokenClient,
                                     long defaultInstallationId, List<String> mintRepositories,
                                     Clock clock) {
        this.jwtFactory = Objects.requireNonNull(jwtFactory);
        this.tokenClient = Objects.requireNonNull(tokenClient);
        this.defaultInstallationId = defaultInstallationId;
        this.mintRepositories = mintRepositories == null ? List.of() : List.copyOf(mintRepositories);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public String token(TokenScope scope) {
        return token(defaultInstallationId, scope);
    }

    @Override
    public String token(long installationId, TokenScope scope) {
        Objects.requireNonNull(scope, "scope");
        CacheKey key = new CacheKey(installationId, scope);
        // computeIfAbsent 的 bin 锁即同键互斥：并发下同键至多一次铸造在飞（M0 单 worker 足够，P9）
        InstallationTokenClient.MintedToken minted = cache.computeIfAbsent(key,
                k -> mintFresh(k.installationId(), k.scope()));
        if (!freshEnough(minted)) {
            // 临期/过期：替换式重铸（put 覆盖；并发下可能多铸一次，幂等无害）
            minted = mintFresh(installationId, scope);
            cache.put(key, minted);
        }
        return minted.token();
    }

    private boolean freshEnough(InstallationTokenClient.MintedToken token) {
        return token.expiresAt().isAfter(clock.instant().plus(REFRESH_MARGIN));
    }

    private InstallationTokenClient.MintedToken mintFresh(long installationId, TokenScope scope) {
        String jwt = jwtFactory.createJwt(clock.instant());
        InstallationTokenClient.MintedToken minted =
                tokenClient.mint(installationId, scope, mintRepositories, jwt);
        if (minted.expiresAt().isBefore(Instant.now(clock))) {
            throw new IllegalStateException("铸造得到的 token 已过期（对端时钟异常？）");
        }
        return minted;
    }
}

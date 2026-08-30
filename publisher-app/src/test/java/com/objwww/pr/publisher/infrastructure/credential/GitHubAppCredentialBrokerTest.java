package com.objwww.pr.publisher.infrastructure.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-08：token 缓存语义——同 (installation, scope) 命中；不同 scope/installation 不串用；
 * TTL 边界（过期前 5 分钟）刷新。铸造客户端用计数假实现，时钟可控。
 */
class GitHubAppCredentialBrokerTest {

    /** 可控时钟 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(t -> t.plusSeconds(seconds));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** 计数假铸造：每次铸造返回独立 token，TTL 1 小时（以受控时钟为基准） */
    private static final class FakeMinter implements InstallationTokenClient {
        final AtomicInteger mintCount = new AtomicInteger();
        final AtomicReference<Long> lastInstallationId = new AtomicReference<>();
        private final Clock clock;

        private FakeMinter(Clock clock) {
            this.clock = clock;
        }

        @Override
        public MintedToken mint(long installationId, TokenScope scope, List<String> repositories,
                                String appJwt) {
            lastInstallationId.set(installationId);
            int n = mintCount.incrementAndGet();
            return new MintedToken("tok-" + n, clock.instant().plusSeconds(3600));
        }
    }

    private MutableClock clock;
    private FakeMinter minter;
    private GitHubAppCredentialBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        minter = new FakeMinter(clock);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        AppJwtFactory jwtFactory = new AppJwtFactory(1L,
                (RSAPrivateKey) gen.generateKeyPair().getPrivate());
        broker = new GitHubAppCredentialBroker(jwtFactory, minter, 4242L, List.of(), clock);
    }

    @Test
    void sameInstallationAndScopeHitsCache() {
        String first = broker.token(100L, TokenScope.READ);
        String second = broker.token(100L, TokenScope.READ);

        assertThat(second).isEqualTo(first);
        assertThat(minter.mintCount.get()).isEqualTo(1); // 同键命中，不重复铸造
    }

    @Test
    void differentScopesNeverShareToken() {
        String read = broker.token(100L, TokenScope.READ);
        String checks = broker.token(100L, TokenScope.CHECKS_WRITE);
        String pulls = broker.token(100L, TokenScope.PULL_REQUESTS_WRITE);

        assertThat(minter.mintCount.get()).isEqualTo(3);
        assertThat(read).isNotEqualTo(checks);
        assertThat(checks).isNotEqualTo(pulls);
    }

    @Test
    void differentInstallationsNeverShareToken() {
        broker.token(100L, TokenScope.READ);
        broker.token(200L, TokenScope.READ);

        assertThat(minter.mintCount.get()).isEqualTo(2);
    }

    @Test
    void refreshesWithinFiveMinuteMarginBeforeExpiry() {
        broker.token(100L, TokenScope.READ); // mint#1，expires = now+1h

        clock.advanceSeconds(50 * 60); // 距过期还有 10 分钟 > 5 分钟余量：仍命中
        broker.token(100L, TokenScope.READ);
        assertThat(minter.mintCount.get()).isEqualTo(1);

        clock.advanceSeconds(6 * 60); // 距过期 4 分钟 < 5 分钟余量：触发刷新
        String refreshed = broker.token(100L, TokenScope.READ);
        assertThat(minter.mintCount.get()).isEqualTo(2);
        assertThat(refreshed).isNotEqualTo("tok-1");
    }

    @Test
    void expiredTokenIsReminted() {
        broker.token(100L, TokenScope.READ);
        clock.advanceSeconds(3700); // 已过 1 小时 TTL

        broker.token(100L, TokenScope.READ);
        assertThat(minter.mintCount.get()).isEqualTo(2);
    }

    @Test
    void writePathUsesConfiguredDefaultInstallation() {
        broker.token(TokenScope.CHECKS_WRITE);

        assertThat(minter.lastInstallationId.get()).isEqualTo(4242L);
    }
}

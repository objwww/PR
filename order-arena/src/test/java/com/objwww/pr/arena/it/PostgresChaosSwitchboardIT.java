package com.objwww.pr.arena.it;

import com.objwww.pr.arena.application.chaos.ChaosSwitchboard;
import com.objwww.pr.arena.application.chaos.FaultType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-16 切换面板 IT：DB 权威读、target 前缀匹配、TTL 过期即失效、
 * fail-closed（DB 不可达 = 无故障，INV-AM2-2）。
 */
class PostgresChaosSwitchboardIT extends ArenaPostgresITBase {

    private ChaosSwitchboard switchboard() {
        return new ChaosSwitchboard(arenaJdbc, Duration.ZERO);
    }

    @Test
    void target前缀命中_未命中_全局命中() {
        seedChaosSession("f3-sc-it", "F3", "chaos-f3x", "ACTIVE", 0, 600);
        ChaosSwitchboard sb = switchboard();

        Optional<com.objwww.pr.arena.application.chaos.FaultGate.ActiveFault> hit =
                sb.probe(FaultType.F3, "chaos-f3x-123");
        assertThat(hit).isPresent();
        assertThat(hit.get().scenarioId()).isEqualTo("f3-sc-it");
        assertThat(hit.get().target()).isEqualTo("chaos-f3x");

        assertThat(sb.probe(FaultType.F3, "live-other")).isEmpty();
        assertThat(sb.probe(FaultType.F1, "chaos-f3x-123")).isEmpty();
    }

    @Test
    void target为空即全域命中() {
        seedChaosSession("f1-sc-it", "F1", null, "ACTIVE", 0, 600);
        assertThat(switchboard().probe(FaultType.F1, "chaos-whatever-1")).isPresent();
    }

    @Test
    void TTL过期即失效_无需应用清场() {
        UUID sid = seedChaosSession("f3-old", "F3", "chaos-old", "ACTIVE", 0, 600);
        ChaosSwitchboard sb = switchboard();
        assertThat(sb.probe(FaultType.F3, "chaos-old-1")).isPresent();

        expireSession(sid);
        assertThat(sb.probe(FaultType.F3, "chaos-old-1")).isEmpty();
    }

    @Test
    void DB不可达_failClosed_空会话() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
        cfg.setUsername("x");
        cfg.setPassword("x");
        cfg.setConnectionTimeout(300);
        cfg.setMaximumPoolSize(1);
        // 懒初始化：构造成功，失败延迟到首次取连接（fail-closed 面）
        cfg.setInitializationFailTimeout(-1);
        try (HikariDataSource dead = new HikariDataSource(cfg)) {
            ChaosSwitchboard deadSb = new ChaosSwitchboard(JdbcClient.create(dead),
                    Duration.ZERO);
            assertThat(deadSb.probe(FaultType.F3, "chaos-1")).isEmpty();
            assertThat(deadSb.sessions()).isEmpty();
        }
    }
}

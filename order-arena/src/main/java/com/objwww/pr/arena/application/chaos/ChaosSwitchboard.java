package com.objwww.pr.arena.application.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 故障总开关（M2-16）：oa_chaos_session 的数据库权威读面，INV-AM2-2 的唯一判定点。
 *
 * <p>fail-closed：任何 DB 异常 = 无故障（宁可测不出注入，不可无故障时报注入）；
 * TTL 过期在 SQL 内判定（expires_at > now()），不依赖应用时钟清场。
 * 内存缓存只作可丢优化（AM2 v3.0 §3.2）：TTL 秒级、崩溃即失、异常即弃。
 *
 * <p>target 匹配（selector 语义）：NULL = 全域；否则 correlationId 以 target 为前缀
 * （E2E 以 chaos-&lt;scenario 短码-…&gt; 前缀圈定靶面）。
 */
public class ChaosSwitchboard implements FaultGate {

    private static final Logger log = LoggerFactory.getLogger(ChaosSwitchboard.class);

    /** 会话只读视图（恢复驱动/审计共用） */
    public record SessionView(String scenarioId, FaultType faultType, String target,
                              long generation, String state) {
    }

    private record CacheLine(Instant loadedAt, List<ActiveFault> faults) {
    }

    private final JdbcClient jdbc;
    private final Duration cacheTtl;
    private volatile CacheLine cache = new CacheLine(Instant.EPOCH, List.of());

    public ChaosSwitchboard(JdbcClient jdbc, Duration cacheTtl) {
        this.jdbc = jdbc;
        this.cacheTtl = cacheTtl;
    }

    @Override
    public Optional<ActiveFault> probe(FaultType type, String correlationId) {
        return snapshot().stream()
                .filter(f -> f.type() == type)
                .filter(f -> matches(f.target(), correlationId))
                .findFirst();
    }

    /** 恢复驱动读面：ACTIVE（F2 注入）与 RECOVERING（恢复执行）全会话，同一 fail-closed 纪律 */
    public List<SessionView> sessions() {
        try {
            return jdbc.sql("""
                    select scenario_id, fault_type, target, generation, state
                    from arena.oa_chaos_session
                    where state in ('ACTIVE','RECOVERING')
                    order by created_at
                    """)
                    .query((rs, i) -> new SessionView(
                            rs.getString("scenario_id"),
                            FaultType.valueOf(rs.getString("fault_type")),
                            rs.getString("target"),
                            rs.getLong("generation"),
                            rs.getString("state")))
                    .list();
        } catch (DataAccessException e) {
            log.warn("chaos 会话读面 fail-closed: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    private List<ActiveFault> snapshot() {
        CacheLine line = cache;
        if (line.loadedAt().plus(cacheTtl).isAfter(Instant.now())) {
            return line.faults();
        }
        try {
            List<ActiveFault> fresh = jdbc.sql("""
                    select fault_type, scenario_id, target, generation
                    from arena.oa_chaos_session
                    where state = 'ACTIVE' and expires_at > now()
                    """)
                    .query((rs, i) -> new ActiveFault(
                            FaultType.valueOf(rs.getString("fault_type")),
                            rs.getString("scenario_id"),
                            rs.getString("target"),
                            rs.getLong("generation")))
                    .list();
            cache = new CacheLine(Instant.now(), fresh);
            return fresh;
        } catch (DataAccessException e) {
            // fail-closed（INV-AM2-2）：异常 = 无故障；缓存同时作废
            cache = new CacheLine(Instant.EPOCH, List.of());
            log.warn("chaos 开关读面 fail-closed: {}", e.getMostSpecificCause().getMessage());
            return List.of();
        }
    }

    private boolean matches(String target, String correlationId) {
        return target == null || target.isBlank()
                || (correlationId != null && correlationId.startsWith(target));
    }
}

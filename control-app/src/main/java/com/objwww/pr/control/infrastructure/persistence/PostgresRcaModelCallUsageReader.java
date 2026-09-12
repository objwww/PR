package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RcaModelCallUsageReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RcaModelCallUsageReader} 的 Postgres 实现（§三.5 费用透出，V48 rca_model_call）。
 * 聚合口径见接口头注：tokens 只加已回报行（usage jsonb
 * {prompt_tokens, completion_tokens}，与 PostgresRcaModelCallLedger.succeed 写面同键）；
 * usageMissing = cost_micros IS NULL 行数；currency/pricingVersion 仅在已定价行
 * 取值唯一时透出（混合 → null 如实，不硬拼）。
 */
public class PostgresRcaModelCallUsageReader implements RcaModelCallUsageReader {

    private final JdbcClient jdbc;

    public PostgresRcaModelCallUsageReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<RunUsage> summarizeByRun(UUID runId) {
        Aggregate agg = jdbc.sql("""
                SELECT count(*) AS call_count,
                       coalesce(sum((usage->>'prompt_tokens')::bigint), 0) AS tokens_in,
                       coalesce(sum((usage->>'completion_tokens')::bigint), 0) AS tokens_out,
                       sum(cost_micros)::bigint AS cost_micros,
                       count(*) FILTER (WHERE cost_micros IS NULL) AS usage_missing
                  FROM rca_model_call
                 WHERE run_id = :runId
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new Aggregate(
                        rs.getLong("call_count"),
                        rs.getLong("tokens_in"),
                        rs.getLong("tokens_out"),
                        rs.getObject("cost_micros", Long.class),
                        rs.getLong("usage_missing")))
                .single();
        if (agg.callCount() == 0) {
            return Optional.empty();
        }
        // 已定价行的币种/定价版本：唯一才透出（混合口径不硬拼）
        List<String[]> priced = jdbc.sql("""
                SELECT DISTINCT currency, pricing_version
                  FROM rca_model_call
                 WHERE run_id = :runId AND cost_micros IS NOT NULL
                """)
                .param("runId", runId)
                .query((rs, rowNum) -> new String[]{
                        rs.getString("currency"), rs.getString("pricing_version")})
                .list();
        String currency = priced.size() == 1 ? priced.get(0)[0] : null;
        String pricingVersion = priced.size() == 1 ? priced.get(0)[1] : null;
        return Optional.of(new RunUsage(agg.callCount(), agg.tokensIn(), agg.tokensOut(),
                agg.costMicros(), agg.usageMissing(), currency, pricingVersion));
    }

    private record Aggregate(long callCount, long tokensIn, long tokensOut,
                             Long costMicros, long usageMissing) {
    }
}

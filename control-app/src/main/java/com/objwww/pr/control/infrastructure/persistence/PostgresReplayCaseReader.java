package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.eval.domain.repository.ReplayCaseReader;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ReplayCaseReader} 的 Postgres 实现（P2）：dataset_version.version 精确键
 * 匹配 + 适用期半开区间过滤（与 findCasesValidAt 同口径）；payload/content_digest
 * 原文上抛，解析归 DatasetCaseMapper。HOLDOUT 行由 V21 RLS 滤除——读面与执行面
 * 同视界。空版本串直接空表（不拼退化查询）。
 */
public class PostgresReplayCaseReader implements ReplayCaseReader {

    private final JdbcClient jdbc;

    public PostgresReplayCaseReader(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<ReplayCaseRow> listReplayCases(String datasetVersion) {
        if (datasetVersion == null || datasetVersion.isBlank()) {
            return List.of();
        }
        Instant now = Instant.now();
        return jdbc.sql("""
                        select dv.id as dataset_version_id, dv.name, dv.version,
                               cv.case_key, cv.scenario_family_id,
                               cv.payload::text as payload_json,
                               cv.content_digest, cv.valid_from
                          from dataset_version dv
                          join case_version cv on cv.dataset_version_id = dv.id
                         where dv.version = :version
                           and cv.valid_from <= :now
                           and (cv.valid_to is null or cv.valid_to > :now)
                         order by dv.created_at asc, cv.case_key asc
                        """)
                .param("version", datasetVersion)
                .param("now", Timestamp.from(now))
                .query((rs, i) -> new ReplayCaseRow(
                        rs.getObject("dataset_version_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("version"),
                        rs.getString("case_key"),
                        rs.getString("scenario_family_id"),
                        rs.getString("payload_json"),
                        rs.getString("content_digest"),
                        rs.getTimestamp("valid_from").toInstant()))
                .list();
    }
}

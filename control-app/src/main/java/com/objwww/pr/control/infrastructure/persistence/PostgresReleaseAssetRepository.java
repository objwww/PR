package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * release_asset 的 Postgres 实现（V60；EN-01）。行 immutable（V60 只授
 * select,insert），(kind,digest) 唯一约束 = 注册幂等锚（DuplicateKey → false）。
 * 读路径经 ReleaseAsset 构造期重新深冻结 + 密钥扫描 + 形状校验（防御纵深，
 * 与 PostgresConfigBundleRepository 同律）。
 */
public class PostgresReleaseAssetRepository implements ReleaseAssetRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INSERT_SQL = """
            INSERT INTO release_asset (
                asset_kind, asset_digest, content, created_by, created_at
            ) VALUES (
                :kind, :digest, CAST(:content AS jsonb), :createdBy, :createdAt
            )
            """;

    private static final String FIND_SQL = """
            SELECT asset_kind, asset_digest, content, created_by, created_at
              FROM release_asset
             WHERE asset_kind = :kind AND asset_digest = :digest
            """;

    private final JdbcClient jdbc;

    public PostgresReleaseAssetRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource 不得为 null"));
    }

    @Override
    public boolean insert(ReleaseAsset asset) {
        try {
            jdbc.sql(INSERT_SQL)
                    .param("kind", asset.kind())
                    .param("digest", asset.assetDigest().hex())
                    .param("content", writeJson(asset.content()))
                    .param("createdBy", asset.createdBy())
                    .param("createdAt", Timestamp.from(asset.createdAt()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
        return jdbc.sql(FIND_SQL)
                .param("kind", kind)
                .param("digest", digest.hex())
                .query((rs, i) -> new ReleaseAsset(rs.getString("asset_kind"),
                        new Digest(rs.getString("asset_digest")),
                        readJson(rs.getString("content")),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    // ------------------------------------------------------------------ 内部

    private static String writeJson(Map<String, Object> content) {
        try {
            return JSON.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("资产 content 序列化失败", e);
        }
    }

    private static Map<String, Object> readJson(String content) {
        try {
            return JSON.readValue(content,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("资产 content 解析失败", e);
        }
    }
}

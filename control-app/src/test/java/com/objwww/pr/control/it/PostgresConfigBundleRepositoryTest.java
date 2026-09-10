package com.objwww.pr.control.it;

import com.objwww.pr.control.infrastructure.persistence.PostgresConfigBundleRepository;
import com.objwww.pr.control.release.application.ConfigBundleService;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * config_bundle / config_bundle_active 真 PG 组件测试（M5-09；落码方案 §M5-09④
 * IT 面）：发布幂等锚（同 digest 唯一拒绝）、单事务原子激活（pointer CAS 无半激活
 * 态，并发败者零改写）、回滚 = pointer 指回且历史行零改写（INV-AM5-5）、V24 授权面
 * （bundle 只 select,insert；pointer 免 delete；eval_app 全零）。
 * 本机无 Docker 自动跳过（真证据待 195 释放后统一补）。
 */
class PostgresConfigBundleRepositoryTest extends PostgresITBase {

    private ConfigBundleRepository repo;
    private ConfigBundleService service;

    @BeforeEach
    void setUp() {
        // V24 两表不在基座 TRUNCATE 清单（AM5 表惯例自清）；pointer 先删再重种未激活行
        adminJdbc.sql("DELETE FROM config_bundle_active").update();
        adminJdbc.sql("DELETE FROM config_bundle").update();
        adminJdbc.sql("INSERT INTO config_bundle_active (id) VALUES (1)").update();

        repo = new PostgresConfigBundleRepository(controlDataSource());
        service = new ConfigBundleService(repo,
                new com.objwww.pr.control.infrastructure.persistence.PostgresReleaseAssetRepository(
                        controlDataSource()));
    }

    private static Map<String, Object> content(String promptVersion) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policy_version", "policy-2026-09");
        content.put("prompt_version", promptVersion);
        return content;
    }

    @Test
    void publishActivateRollbackFlowKeepsHistoryUntouched() {
        ConfigBundleService.PublishResult first = service.publish(content("v7"), "op-1");
        ConfigBundleService.PublishResult second = service.publish(content("v8"), "op-1");
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(second.revision()).isEqualTo(2L);

        // 激活 d1 → d2 → 回滚 d1：pointer 全程 CAS
        assertThat(service.activate(first.bundleDigest(), "op-1").moved()).isTrue();
        assertThat(service.activate(second.bundleDigest(), "op-1").moved()).isTrue();

        // 回滚前快照两行历史全貌（admin 视角 to_jsonb；BA-41：SimplePropertyRowMapper
        // 不支持 Map.class，单列 jsonb 取文本——jsonb 输出确定性，字符串全等更强）
        String before = adminJdbc.sql(
                        "SELECT jsonb_object_agg(bundle_digest, to_jsonb(b)) FROM config_bundle b")
                .query((rs, i) -> rs.getString(1)).single();

        Instant rollbackAt = Instant.now();
        ConfigBundleService.ActivationResult rollback =
                service.rollback(first.bundleDigest(), "op-2");

        assertThat(rollback.moved()).isTrue();
        assertThat(repo.activeDigest()).contains(first.bundleDigest());
        ConfigBundleRepository.ActivePointer pointer = repo.findActivePointer().orElseThrow();
        assertThat(pointer.bundleDigest()).isEqualTo(first.bundleDigest());
        assertThat(pointer.revision()).isEqualTo(1L);
        assertThat(pointer.activatedAt()).isAfter(rollbackAt.minusSeconds(5));

        // 历史行零改写（INV-AM5-5）：两行 digest→行全貌 映射与回滚前逐字段一致
        assertThat(adminJdbc.sql(
                        "SELECT jsonb_object_agg(bundle_digest, to_jsonb(b)) FROM config_bundle b")
                .query((rs, i) -> rs.getString(1)).single())
                .isEqualTo(before);
        assertThat(count("config_bundle")).isEqualTo(2);
    }

    @Test
    void sameDigestIsRejectedByUniqueAnchorAndServiceReplays() {
        ConfigBundleService.PublishResult first = service.publish(content("v7"), "op-1");

        // DB 面：同 digest 直插（异 id/异 revision）= DuplicateKey
        assertThatThrownBy(() -> adminJdbc.sql("""
                        INSERT INTO config_bundle (
                            id, bundle_digest, revision, content, created_by, created_at
                        ) VALUES (:id, :d, 99, '{}'::jsonb, 'intruder', now())
                        """)
                .param("id", java.util.UUID.randomUUID())
                .param("d", first.bundleDigest().hex()).update())
                .as("digest 唯一 = 发布幂等锚的 DB 面")
                .isInstanceOf(DuplicateKeyException.class);

        // 服务面：同内容重发 = 幂等重放（同 digest 同 revision，不新增行）
        ConfigBundleService.PublishResult replay = service.publish(content("v7"), "op-2");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.bundleDigest()).isEqualTo(first.bundleDigest());
        assertThat(replay.revision()).isEqualTo(first.revision());
        assertThat(count("config_bundle")).isEqualTo(1);
    }

    @Test
    void activationIsCasAndRacersLoseWithoutSideEffects() {
        ConfigBundleService.PublishResult published = service.publish(content("v7"), "op-1");

        // 未激活态首激活：expectedCurrent=null 语义（IS NOT DISTINCT FROM NULL）
        assertThat(repo.activate(published.bundleDigest(), null, "op-1", Instant.now())).isTrue();

        // 旁路/竞败：期望 stale null 但实际已激活 → 0 行，指针不动
        Digest ghost = Digest.sha256Of("ghost");
        assertThat(repo.activate(ghost, null, "racer", Instant.now())).isFalse();
        assertThat(repo.activeDigest()).contains(published.bundleDigest());

        // 竞败零副作用：ghost 从未成行
        assertThat(repo.findByDigest(ghost)).isEmpty();
    }

    @Test
    void v24GrantsFreezeImmutableHistoryAndPointer() {
        ConfigBundleService.PublishResult published = service.publish(content("v7"), "op-1");
        service.activate(published.bundleDigest(), "op-1");

        // bundle 历史：control_app 有 select+insert，UPDATE/DELETE 授权面为 0
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql(
                                "UPDATE config_bundle SET created_by = 'tampered' WHERE revision = 1")
                        .update());
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql("DELETE FROM config_bundle").update());

        // pointer：允许 update（激活/回滚唯一写面），delete 拒绝
        controlJdbc.sql("""
                        UPDATE config_bundle_active SET activated_by = 'op-x' WHERE id = 1
                        """).update();
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> controlJdbc.sql("DELETE FROM config_bundle_active").update());

        // eval_app 全零（V24 revoke all）
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql("SELECT count(*) FROM config_bundle")
                        .query(Long.class).single());
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> evalJdbc.sql("SELECT count(*) FROM config_bundle_active")
                        .query(Long.class).single());
    }
}

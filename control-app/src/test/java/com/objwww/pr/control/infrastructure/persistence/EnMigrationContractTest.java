package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EN 线迁移本地静态门（V60/V61，号段 EN=V60 起——2026-09-11 三线定死，增强线
 * 方案 §6.1）：Docker 不可用时也锁住发布资产/发布资格两表的关键结构、门语义列
 * 与授权边界（INV-AM5-5 同律：资产面 immutable、资格面撤销零 delete）。真 PG
 * 约束/授权行为由 PostgresReleaseAssetRepositoryTest / PostgresReleaseActivationIT
 * （Testcontainers IT，195 补真证据）覆盖。范式沿 Am5MigrationContractTest。
 */
class EnMigrationContractTest {

    private static final Path V60 = Path.of(
            "src/main/resources/db/migration/V60__en01_release_asset.sql");

    private static final Path V61 = Path.of(
            "src/main/resources/db/migration/V61__en02_release_qualification.sql");

    private static String normalized(Path migration) throws IOException {
        return Files.readString(migration).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v60ReleaseAssetIsContentAddressedImmutableFace() throws IOException {
        String sql = normalized(V60);

        assertThat(sql)
                .contains("create table release_asset")
                // 身份 = (kind, digest)：内容寻址（S10 锚）——同 kind 同 digest 重发幂等
                .contains("asset_kind text not null")
                .contains("asset_digest char(64) not null")
                .contains("content jsonb not null check (jsonb_typeof(content) = 'object')")
                .contains("constraint uq_release_asset_kind_digest unique (asset_kind, asset_digest)")
                // immutable 面：control_app 只 select,insert
                .contains("grant select, insert on release_asset to control_app")
                .contains("revoke update, delete on release_asset from control_app");
    }

    @Test
    void v61ReleaseQualificationPinsGateContractAndRevocationFace() throws IOException {
        String sql = normalized(V61);

        assertThat(sql)
                .contains("create table release_qualification")
                // 证明身份列：候选/基线 FK 钉已发布面（P11 回滚同门的 DB 前提）
                .contains("candidate_digest char(64) not null references config_bundle (bundle_digest)")
                .contains("baseline_digest char(64) references config_bundle (bundle_digest)")
                // 质量判定与费用对账分开记账（E05/S09：MATCHED 不代替 PASS）
                .contains("check (quality_verdict in ('pass', 'fail', 'inconclusive'))")
                .contains("check (usage_status in ('matched', 'mismatch', 'unknown'))")
                // 撤销三件套一致性（at/by/reason 要么全空要么全在）
                .contains("constraint ck_release_qualification_revoke")
                .contains("check (revoked_at is null or (revoked_by is not null and revoked_reason is not null))")
                // 授权面：撤销 = UPDATE 三列，delete 零授；他角色全零
                .contains("grant select, insert, update on release_qualification to control_app")
                .contains("revoke delete on release_qualification from control_app")
                .contains("revoke all on release_qualification");
    }
}

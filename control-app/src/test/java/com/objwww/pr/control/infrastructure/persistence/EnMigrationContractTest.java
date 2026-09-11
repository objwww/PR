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

    private static final Path V62 = Path.of(
            "src/main/resources/db/migration/V62__en09_eval_grader_version.sql");

    private static final Path V63 = Path.of(
            "src/main/resources/db/migration/V63__en04_run_config_epoch.sql");

    private static final Path V64 = Path.of(
            "src/main/resources/db/migration/V64__en06_mcp_server_registry.sql");

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

    @Test
    void v62AddsGraderVersionToEvalRunIdentity() throws IOException {
        String sql = normalized(V62);

        assertThat(sql)
                .contains("alter table eval_run add column grader_version text")
                // 历史批次留空 = EN-09 前评分器版本未入账（E11 归属面：null 可区分）
                .contains("comment on column eval_run.grader_version");
    }

    @Test
    void v63RunConfigEpochHistoryPinsAppendOnlyUniqueFace() throws IOException {
        String sql = normalized(V63);

        assertThat(sql)
                .contains("create table rca_run_config_epoch")
                // §185/§207：config_epoch→release_digest 追加历史；UNIQUE(run_id, config_epoch)
                .contains("constraint pk_rca_run_config_epoch primary key (run_id, config_epoch)")
                .contains("release_digest char(64) not null")
                .contains("references rca_run (id)")
                // 追加史 immutable：control_app 只 select,insert
                .contains("grant select, insert on rca_run_config_epoch to control_app")
                .contains("revoke update, delete on rca_run_config_epoch from control_app")
                // §227：既有命令账本扩容复用——CONFIG_SWITCH 命令 + WAITING_SAFE_POINT/EXPIRED 态
                .contains("alter table operator_command alter column state type varchar(24)")
                .contains("'config_switch'")
                .contains("'waiting_safe_point'")
                .contains("'expired'");
    }

    @Test
    void v64McpServerRegistryPinsGenerationAndGrantFace() throws IOException {
        String sql = normalized(V64);

        assertThat(sql)
                .contains("create table mcp_server_registry")
                // §2.3：name/transport/url-or-command/args/headers_ref/enabled/generation/updated_at
                .contains("constraint pk_mcp_server_registry primary key (name)")
                .contains("check (transport in ('streamable_http', 'stdio'))")
                .contains("endpoint text not null")
                .contains("args jsonb not null")
                .contains("headers_ref text")
                .contains("enabled boolean not null")
                .contains("generation bigint not null")
                .contains("updated_at timestamptz not null")
                // 管理面（register/disable/enable/deregister）在控制面 REST：control_app 全权；
                // eval_app/notify_app 全零（MCP 注册表不进评估/通知面）
                .contains("grant select, insert, update, delete on mcp_server_registry to control_app")
                .contains("revoke all on mcp_server_registry from eval_app")
                .contains("revoke all on mcp_server_registry from notify_app");
    }

    /**
     * V89（BA-122）：EN-07 固定语料两 kind 的词表扩集——产品域 ReleaseAsset 五 kind
     * （RUNBOOK_DOC/RUNBOOK_CATALOG，"EN-01 三类同律"）必须有 DB 侧 check 对应面；
     * 原位扩集（V12 ck_rca_run_finish 同律：drop + add 同名约束），身份/授权面零改动。
     */
    @Test
    void v89ReleaseAssetKindCheckCoversRunbookCorpusKinds() throws IOException {
        Path v89 = Path.of(
                "src/main/resources/db/migration/V89__en07_release_asset_kinds.sql");
        String sql = normalized(v89);

        assertThat(sql)
                .contains("alter table release_asset drop constraint release_asset_asset_kind_check")
                .contains("alter table release_asset add constraint release_asset_asset_kind_check")
                .contains("'prompt', 'skill', 'tool_schema'")
                .contains("'runbook_doc', 'runbook_catalog'");
    }
}

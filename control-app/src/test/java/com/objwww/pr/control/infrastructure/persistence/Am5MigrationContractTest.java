package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20（AM5 M5-01）本地静态门：Docker 不可用时也锁住数据集版本两表的关键结构
 * 与 insert-only 授权边界（INV-AM5-1：历史不可覆盖）。真 PG 约束/授权行为由
 * PostgresDatasetVersionRepositoryTest（Testcontainers IT，195 补真证据）覆盖。
 * 范式沿 M7MigrationContractTest：规范化大小写/空白后整体比对。
 */
class Am5MigrationContractTest {

    private static final Path V20 = Path.of(
            "src/main/resources/db/migration/V20__am5_dataset_version.sql");

    private static String normalized() throws IOException {
        return Files.readString(V20).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v20CreatesDatasetAndCaseVersionTablesWithNineSourceFields() throws IOException {
        String sql = normalized();

        assertThat(sql)
                .contains("create table dataset_version")
                // 来源九字段（方案 §3.1 v1.1 冻结清单）
                .contains("source text not null")
                .contains("name text not null")
                .contains("version text not null")
                .contains("source_uri text not null")
                .contains("license text not null")
                .contains("access_class text not null")
                .contains("content_digest char(64) not null")
                .contains("adapter_version text not null")
                .contains("imported_at timestamptz not null")
                // 分级 + 四分区归属 + 族清单摘要（落码方案 §M5-01②）
                .contains("source_class varchar(24) not null")
                .contains("partition_class varchar(16) not null")
                .contains("scenario_family_digest char(64) not null")
                .contains("check (source_class in ('private','public_benchmark'))")
                .contains("check (partition_class in ('tuning','validation','holdout','redteam'))")
                .contains("create table case_version")
                .contains("scenario_family_id text not null")
                .contains("valid_from timestamptz not null")
                .contains("valid_to timestamptz")
                .contains("source_artifact_ref text")
                .contains("payload jsonb not null")
                // 落码方案要求 comment on table
                .contains("comment on table dataset_version")
                .contains("comment on table case_version");
    }

    @Test
    void v20HistoryIsNotOverwritable() throws IOException {
        String sql = normalized();

        // insert-only：同 (name,version) 数据集版本禁重生；同 (dataset,case_key)
        // 案例版本禁覆盖（纠错 = 新 dataset_version 携带修正行）；适用期窗口合法性
        assertThat(sql)
                .contains("constraint uq_dataset_version unique (name, version)")
                .contains("constraint uq_case_version unique (dataset_version_id, case_key)")
                .contains("check (valid_to is null or valid_to > valid_from)")
                .contains("references dataset_version(id)");
    }

    @Test
    void v20GrantsInsertOnlyToEvalAppAndZeroToOtherRoles() throws IOException {
        String sql = normalized();

        // eval_app = eval-runner 独立身份：只 select+insert，无任何 UPDATE/DELETE 授权
        assertThat(sql)
                .contains("grant select, insert on dataset_version, case_version to eval_app")
                .doesNotContainPattern("grant [a-z ,]*update on dataset_version")
                .doesNotContainPattern("grant [a-z ,]*update on case_version")
                .doesNotContainPattern("grant [a-z ,]*delete on dataset_version")
                .doesNotContainPattern("grant [a-z ,]*delete on case_version")
                // 生产角色 + public 显式零权限（V10 惯例）
                .contains("revoke all on dataset_version, case_version"
                        + " from control_app, publisher_app, notify_app, public")
                // arena 域角色条件化幂等 revoke（干净 IT 库可能不存在）
                .contains("if exists (select from pg_roles where rolname = 'arena_app')")
                .contains("if exists (select from pg_roles where rolname = 'chaos_admin_app')");
    }
}

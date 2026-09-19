package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rca_claim 评分贯通列本地静态门（范式沿 Am5MigrationContractTest：规范化
 * 大小写/空白后整体比对）：Docker 不可用时也锁住 V151 symptom_codes 列的关键
 * 结构。真 PG 行为由 Testcontainers IT 覆盖。
 *
 * <p>V151 缘由（BA-148 同族）：NativeReportAdapter 旧实现把证据来源标签塞进
 * claims[].symptom_codes 评分契约槽位（195 实证批 aa7f25b4 tp=0/fp=99/fn=60
 * 结构性恒 miss）；本列承接主 Agent FINAL 提案的症状码（告警名），可空=诚实
 * 降级，禁止来源标签冒充。
 */
class RcaClaimMigrationContractTest {

    private static final Path V151 = Path.of(
            "src/main/resources/db/migration/V151__rca_claim_symptom_codes.sql");

    private static String normalized(Path migration) throws IOException {
        return Files.readString(migration).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v151AddsNullableSymptomCodesJsonbColumn() throws IOException {
        String sql = normalized(V151);

        // 可空 jsonb（null=未声明，诚实降级）+ 列注释纪律（V147 同律）
        assertThat(sql)
                .contains("alter table rca_claim add column symptom_codes jsonb")
                .doesNotContain("symptom_codes jsonb not null")
                .contains("comment on column rca_claim.symptom_codes");
    }

    @Test
    void v151ReusesTableLevelGrantWithoutNewGrantStatements() throws IOException {
        // V17 表级授权自动覆盖新增列（V37/V147 同律）——本迁移不得新开授权面；
        // 剥离注释行后断言（头注释按 V147 格式引用 V17 授权原文，不误中）
        String statements = String.join("\n", Files.readString(V151).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .toList()).toLowerCase();

        assertThat(statements)
                .doesNotContainPattern("grant .* on rca_claim")
                .doesNotContainPattern("revoke .* on rca_claim");
    }
}

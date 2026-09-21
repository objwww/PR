package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-190（W3）rca_tool_invocation.reason_detail 本地静态门（范式沿
 * RcaClaimMigrationContractTest）：Docker 不可用时也锁住 V155 的关键结构。
 * 定谳背景：账本只有 reason_code=INVALID_INPUT 无具体消息，BoundedLlmRoleRunner
 * 的拒因 WARN 日志随容器重建丢失，事后无法回答"为什么被拒"。
 */
class Ba190ReasonDetailMigrationContractTest {

    private static final Path V155 = Path.of(
            "src/main/resources/db/migration/V155__ba190_tool_invocation_reason_detail.sql");

    private static String normalized(Path migration) throws IOException {
        return Files.readString(migration).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v155AddsNullableReasonDetailTextColumn() throws IOException {
        String sql = normalized(V155);

        // 可空 text（null=旧行/无详情路径，诚实降级）+ 列注释纪律（V151 同律）
        assertThat(sql)
                .contains("alter table rca_tool_invocation add column reason_detail text")
                .doesNotContain("reason_detail text not null")
                .contains("comment on column rca_tool_invocation.reason_detail");
    }

    @Test
    void v155ReusesTableLevelGrantWithoutNewGrantStatements() throws IOException {
        // V15 表级授权自动覆盖新增列（V151 同律）——本迁移不得新开授权面；
        // 剥离注释行后断言（头注释引用 V15 授权原文，不误中）
        String statements = String.join("\n", Files.readString(V155).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .toList()).toLowerCase();

        assertThat(statements)
                .doesNotContainPattern("grant .* on rca_tool_invocation")
                .doesNotContainPattern("revoke .* on rca_tool_invocation");
    }
}

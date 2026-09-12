package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UX 线迁移本地静态门（范式沿 EnMigrationContractTest——Docker 不可用时锁住关键
 * 结构语义；真 PG 行为由 PostgresIncidentQueryReaderIT / PostgresOperatorCaseIT
 * 覆盖，195 补真证据）。独立成类：EnMigrationContractTest 是 EN/R 线在飞文件，不叠改。
 */
class UxMigrationContractTest {

    private static final Path V94 = Path.of(
            "src/main/resources/db/migration/V94__ux03_case_incident_link.sql");

    private static String normalized(Path migration) throws IOException {
        return Files.readString(migration).toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * V94（UX-03，方案 §三.2 负责人列前置）：operator_case.incident_id 可空来源引用列
     * （V26 run_id 同律——无 FK，不硬绑 alert 域生命周期）+ open case 反查部分索引；
     * 不加部分唯一约束（同 incident 跨 episode/多 fingerprint 合法多单）。
     */
    @Test
    void v94CaseIncidentLinkIsNullableSourceRefWithOpenIndex() throws IOException {
        String sql = normalized(V94);

        assertThat(sql)
                .contains("alter table operator_case add column incident_id uuid")
                .contains("comment on column operator_case.incident_id")
                // 读面 = 告警列表 owner 列的 open case 反查（OPEN/ACKED 部分索引）
                .contains("create index ix_operator_case_incident_open on operator_case(incident_id)")
                .contains("where status in ('open','acked')")
                // 来源引用列（V26 run_id 同律）：无 FK、无唯一约束
                .doesNotContain("references incident")
                .doesNotContain("unique");
    }
}

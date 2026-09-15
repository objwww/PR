package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V111（PA-A1）本地静态门：rca_attempt 进度双列 + LIVE_BUT_STUCK 完成种约束放宽。
 * Docker 不可用时也锁住迁移关键语义（真实 PG 行为由 Testcontainers IT 覆盖）。
 */
class Pa1AttemptProgressMigrationContractTest {

    private static final Path V111 = Path.of(
            "src/main/resources/db/migration/V111__pa1_attempt_progress.sql");

    private String normalized() throws IOException {
        return String.join(" ", Files.readAllLines(V111)).replaceAll("\\s+", " ");
    }

    @Test
    void v111_addsAttemptProgressColumnsAsTimestamptz() throws IOException {
        String sql = normalized();
        assertThat(sql).contains("alter table rca_attempt add column last_activity_at timestamptz");
        assertThat(sql).contains(
                "alter table rca_attempt add column last_meaningful_progress_at timestamptz");
    }

    @Test
    void v111_widensCompletionKindWithLiveButStuck() throws IOException {
        String sql = normalized();
        // 约束重放必须先 drop 旧约束（幂等迁移面无 IF EXISTS 依赖——V108 定义原文锚定）
        assertThat(sql).contains("alter table rca_run drop constraint ck_rca_run_completion_kind");
        assertThat(sql).contains("'LIVE_BUT_STUCK'");
        // 既有三完成种不回退
        assertThat(sql).contains("'SHADOW_EVIDENCE_ONLY'");
        assertThat(sql).contains("'QUEUE_DEADLINE'");
        assertThat(sql).contains("'DEADLINE_EXPIRED'");
    }

    @Test
    void v111_documentsLeaseRenewalIsNotProgress() throws IOException {
        // 评审纪律成文：lease 续租永不回写 progress 列（注释即契约）
        assertThat(normalized()).contains("lease 续租永不回写本列");
    }
}

package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.model.RcaTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-70（M6-01）本地静态门：claimNext 通用领取面只认 driver task_key——NATIVE run
 * 的 DAG 调查任务（investigate-*）由 NativeInvestigationExecutor 在 driver task
 * 内独占驱动；误领会在其 finishTask 提前终结 run。锁定两点：
 * ① Postgres CLAIM_SQL 含 task_key 过滤且键值与 {@link RcaTask} 冻结常量一致
 *    （SR §4.3 增 REPORT_FINALIZE 恢复 task 键；SR §3.2 增影子 Run 排除谓词）；
 * ② 内存 fake（AlertInMemoryStores.Tasks.claimNext）同语义（worker UT 的诚实前提）。
 * 真 PG 行为（SKIP LOCKED 并发）由 PostgresRcaTaskRepositoryIT（195）覆盖。
 */
class PostgresClaimTaskKeyFilterContractTest {

    @Test
    @DisplayName("CLAIM_SQL 含 driver+finalize task_key 过滤与影子排除谓词，键值与 RcaTask 冻结常量逐字一致")
    void claimSqlRestrictedToDriverTaskKeys() {
        String sql = PostgresRcaTaskRepository.CLAIM_SQL.toLowerCase().replaceAll("\\s+", " ");

        // 键值漂移（改名/加键）必须显式过本门：SQL 字面量与域常量逐字比对
        assertThat(sql).contains("t.task_key in ('" + RcaTask.HOLMES_INVESTIGATE.toLowerCase()
                + "', '" + RcaTask.NATIVE_INVESTIGATE.toLowerCase()
                + "', '" + RcaTask.REPORT_FINALIZE.toLowerCase() + "')");
        // SR §3.2：影子 Run 的任务不被生产调度领取（不以占满槽位为隔离机制）
        assertThat(sql).contains("coalesce(r.purpose, 'legacy_unknown') <> 'shadow'");
    }

    @Test
    @DisplayName("内存 fake claimNext 同语义：DAG 任务不可被通用领取，driver/finalize 可，影子排除")
    void inMemoryClaimNextMirrorsDriverKeyFilter() throws IOException {
        String source = Files.readString(Path.of(
                "src/test/java/com/objwww/pr/control/alert/support/AlertInMemoryStores.java"));
        String claimNextBody = source.substring(
                source.indexOf("synchronized Optional<RcaTask> claimNext"));
        String normalized = claimNextBody.toLowerCase().replaceAll("\\s+", " ");

        assertThat(normalized)
                .contains("c-70")
                .contains("holmes_investigate")
                .contains("native_investigate")
                .contains("report_finalize")
                .contains("purposeof(t.runid())");
    }
}

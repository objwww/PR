package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BA-191（V156）service.rollback × flagd 旗标资源真执行放行的本地静态门（范式沿
 * Ba190ReasonDetailMigrationContractTest）：Docker 不可用时也锁住迁移关键结构——
 * 单工具单资源单环境的极小面、control_app 台账授权与 V95 eval_app 同律不开
 * insert、注册表外永远 dry_run 的语义注释。
 */
class Ba191FlagdRollbackUnlockMigrationContractTest {

    private static final Path V156 = Path.of(
            "src/main/resources/db/migration/V156__ba191_flagd_rollback_unlock.sql");

    private static String normalized() throws IOException {
        return Files.readString(V156).toLowerCase().replaceAll("\\s+", " ");
    }

    @Test
    void v156UnlocksOnlyServiceRollbackOnFlagdFlagResource() throws IOException {
        String sql = normalized();

        // 三元极小面：service.rollback × flag://flagd/paymentFailure × production
        assertThat(sql)
                .contains("insert into mutation_unlock_registry")
                .contains("'service.rollback'")
                .contains("'flag://flagd/paymentfailure'")
                .contains("'production'")
                .contains("on conflict (tool_name) do nothing");
        // 旗标资源身份 + 请求键别名（planner 三元 env 比对依赖 inventory 权威面）
        assertThat(sql)
                .contains("insert into resource_inventory")
                .contains("insert into resource_alias");
        // 白名单唯一行（本迁移只放行一条，不把面铺大）
        assertThat(sql.split("insert into mutation_unlock_registry", -1)).hasSize(2);
    }

    @Test
    void v156GrantsControlAppLedgerFaceSameAsEvalAppWithoutInsert() throws IOException {
        String statements = String.join("\n", Files.readString(V156).lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .toList()).toLowerCase();

        // 与 V95 eval_app 同律：select + 列级 update；不开 insert（激活落账仍归 eval 面）
        assertThat(statements)
                .contains("grant select on flagd_restore_ledger to control_app")
                .contains("grant update (state, state_reason, updated_at) on "
                        + "flagd_restore_ledger to control_app")
                .doesNotContain("grant insert on flagd_restore_ledger")
                .doesNotContainPattern("grant .* on change_event");
    }
}

package com.objwww.pr.control.infrastructure.selfcheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Control 启动自检：假探针覆盖每条拒绝路径与放行路径（无 docker 可跑）。
 * 真实 SQL 的 has_table_privilege 语义与 V5 grants 冻结矩阵一致（部署门在 compose 栈验证）。
 * AM1-T00 清障后：PR 域检查项随死代码删除，告警域检查项由 T09 AlertSelfCheck 增补。
 */
class ControlSelfCheckTest {

    /** 干净环境：模型 key 在位 + webhook token 在位 */
    private static Map<String, String> cleanEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_MODEL_API_KEY", "sk-xxx");
        env.put("ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "test-token");
        return env;
    }

    private static DbPrivilegeProbe goodDb() {
        // AM1-T09：AlertSelfCheck 需要 V7 九表权限（SELECT/INSERT/UPDATE，无 DELETE）
        return (table, privilege) -> switch (privilege) {
            case "INSERT", "SELECT", "UPDATE" -> true;
            default -> throw new IllegalArgumentException(privilege);
        };
    }

    @Test
    void passesWithCleanEnvironmentAndFrozenPrivileges() {
        assertThat(ControlSelfCheck.violations(cleanEnv(), goodDb())).isEmpty();
    }

    @Test
    void rejectsMissingModelKey() {
        Map<String, String> env = cleanEnv();
        env.remove("AGENT_MODEL_API_KEY");
        assertThat(ControlSelfCheck.violations(env, goodDb()))
                .anySatisfy(v -> assertThat(v).contains("AGENT_MODEL_API_KEY"));
    }

    @Test
    void rejectsMissingInsertPrivilegeOnModelLedger() {
        DbPrivilegeProbe badDb = (table, privilege) -> switch (privilege) {
            case "INSERT" -> false;
            case "SELECT", "UPDATE" -> true;  // AlertSelfCheck 需要 SELECT/UPDATE
            default -> throw new IllegalArgumentException(privilege);
        };
        assertThat(ControlSelfCheck.violations(cleanEnv(), badDb))
                .anySatisfy(v -> assertThat(v).contains("INSERT").contains("model_call_ledger"));
    }

    @Test
    void aggregatesMultipleViolations() {
        Map<String, String> env = new HashMap<>(); // 缺模型 key 和 webhook token
        DbPrivilegeProbe badDb = (table, privilege) -> switch (privilege) {
            case "INSERT" -> false;
            case "SELECT", "UPDATE" -> true;  // AlertSelfCheck 需要 SELECT/UPDATE
            default -> throw new IllegalArgumentException(privilege);
        };
        List<String> violations = ControlSelfCheck.violations(env, badDb);
        assertThat(violations).hasSizeGreaterThanOrEqualTo(3); // 缺模型 key + 缺 webhook token + 无 INSERT
    }

    @Test
    void runnerRefusesStartupOnViolations() {
        StartupSelfCheckRunner runner = new StartupSelfCheckRunner("control",
                () -> List.of("甲", "乙"));
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("启动自检失败").hasMessageContaining("甲")
                .hasMessageContaining("乙");
    }

    @Test
    void runnerPassesWhenNoViolations() throws Exception {
        new StartupSelfCheckRunner("control", List::of).run(new DefaultApplicationArguments());
    }
}

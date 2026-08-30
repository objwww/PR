package com.objwww.pr.control.infrastructure.selfcheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Control 启动自检（B25/§6.5）：假探针覆盖每条拒绝路径与放行路径（无 docker 可跑）。
 * 真实 SQL 的 has_table_privilege 语义与 V2 grants 冻结矩阵一致（DP-03 在 compose 栈验证）。
 */
class ControlSelfCheckTest {

    /** 干净环境：模型 key 在位；webhook 密钥与只读 token 是 Control 合法变量 */
    private static Map<String, String> cleanEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("AGENT_MODEL_API_KEY", "sk-xxx");
        env.put("GITHUB_WEBHOOK_SECRET", "wh");
        env.put("GITHUB_READONLY_TOKEN", "readonly");
        return env;
    }

    private static DbPrivilegeProbe db(boolean update, boolean insert) {
        return (table, privilege) -> switch (privilege) {
            case "UPDATE" -> update;
            case "INSERT" -> insert;
            default -> throw new IllegalArgumentException(privilege);
        };
    }

    private static DbPrivilegeProbe goodDb() {
        return db(false, true);
    }

    @Test
    void passesWithCleanEnvironmentAndFrozenPrivileges() {
        assertThat(ControlSelfCheck.violations(cleanEnv(), goodDb())).isEmpty();
    }

    @Test
    void rejectsKnownWriteCredentialVariables() {
        for (String name : List.of("GITHUB_APP_KEY", "GITHUB_APP_PRIVATE_KEY",
                "GITHUB_WRITE_TOKEN", "GITHUB_TOKEN", "GH_TOKEN")) {
            Map<String, String> env = cleanEnv();
            env.put(name, "x");
            assertThat(ControlSelfCheck.violations(env, goodDb()))
                    .anySatisfy(v -> assertThat(v).contains(name));
        }
    }

    @Test
    void rejectsPatternMatchedCredentialVariables() {
        // 名单之外、名字模式命中的兜底（如自定义的 *_GITHUB_*_PRIVATE_KEY）
        Map<String, String> env = cleanEnv();
        env.put("MY_GITHUB_APP_PRIVATE_KEY_PEM", "x");
        assertThat(ControlSelfCheck.violations(env, goodDb()))
                .anySatisfy(v -> assertThat(v).contains("MY_GITHUB_APP_PRIVATE_KEY_PEM"));
    }

    @Test
    void rejectsMissingModelKey() {
        Map<String, String> env = cleanEnv();
        env.remove("AGENT_MODEL_API_KEY");
        assertThat(ControlSelfCheck.violations(env, goodDb()))
                .anySatisfy(v -> assertThat(v).contains("AGENT_MODEL_API_KEY"));
    }

    @Test
    void rejectsUpdatePrivilegeOnOutbox() {
        // DP-03：UPDATE 权为 true = AFT-06 冻结被破坏
        assertThat(ControlSelfCheck.violations(cleanEnv(), db(true, true)))
                .anySatisfy(v -> assertThat(v).contains("UPDATE").contains("outbox_command"));
    }

    @Test
    void rejectsMissingInsertPrivilegeOnOutbox() {
        assertThat(ControlSelfCheck.violations(cleanEnv(), db(false, false)))
                .anySatisfy(v -> assertThat(v).contains("INSERT"));
    }

    @Test
    void aggregatesMultipleViolations() {
        Map<String, String> env = new HashMap<>(); // 缺模型 key
        env.put("GITHUB_WRITE_TOKEN", "x"); // 且有写 token
        List<String> violations = ControlSelfCheck.violations(env, db(true, false));
        assertThat(violations).hasSize(4); // 写 token + 缺模型 key + UPDATE + 无 INSERT
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

package com.objwww.pr.control.alert.infrastructure.selfcheck;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSelfCheckTest {

    @Test
    void violations_empty_when_all_requirements_met() {
        Map<String, String> env = Map.of(
                "ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "test-token",
                "HOLMES_API_KEY", "holmes-key"
        );
        var db = fakeDbWithFullPrivileges();

        List<String> violations = AlertSelfCheck.violations(env, db, true);

        assertThat(violations).isEmpty();
    }

    @Test
    void violations_when_webhook_token_missing() {
        Map<String, String> env = Map.of("HOLMES_API_KEY", "holmes-key");
        var db = fakeDbWithFullPrivileges();

        List<String> violations = AlertSelfCheck.violations(env, db, false);

        assertThat(violations)
                .hasSize(1)
                .anyMatch(v -> v.contains("ALERTMANAGER_WEBHOOK_BEARER_TOKEN"));
    }

    @Test
    void violations_when_holmes_key_missing_and_enabled() {
        Map<String, String> env = Map.of("ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "token");
        var db = fakeDbWithFullPrivileges();

        List<String> violations = AlertSelfCheck.violations(env, db, true);

        assertThat(violations)
                .hasSize(1)
                .anyMatch(v -> v.contains("HOLMES_API_KEY"));
    }

    @Test
    void no_holmes_violation_when_disabled() {
        Map<String, String> env = Map.of("ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "token");
        var db = fakeDbWithFullPrivileges();

        List<String> violations = AlertSelfCheck.violations(env, db, false);

        assertThat(violations).isEmpty();
    }

    @Test
    void violations_when_v7_table_privilege_missing() {
        Map<String, String> env = Map.of(
                "ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "token",
                "HOLMES_API_KEY", "key"
        );
        var db = new FakeDbProbe(Map.of(
                "alert_inbox", List.of("SELECT", "INSERT"), // 缺 UPDATE（V7 不检查 DELETE）
                "alert_event", List.of("SELECT", "INSERT", "UPDATE"),
                "incident", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_run", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_task", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_attempt", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_report", List.of("SELECT", "INSERT", "UPDATE"),
                "external_invocation_ledger", List.of("SELECT", "INSERT"),
                "scheduler_slot", List.of("SELECT", "INSERT", "UPDATE")
        ));

        List<String> violations = AlertSelfCheck.violations(env, db, true);

        assertThat(violations)
                .hasSize(1)
                .anyMatch(v -> v.contains("alert_inbox") && v.contains("UPDATE"));
    }

    @Test
    void violations_when_event_ledger_table_missing_insert() {
        Map<String, String> env = Map.of(
                "ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "token"
        );
        var db = new FakeDbProbe(Map.of(
                "alert_inbox", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "alert_event", List.of("SELECT"), // 缺 INSERT
                "incident", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_run", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_task", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_attempt", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_report", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "external_invocation_ledger", List.of("SELECT"), // 缺 INSERT
                "scheduler_slot", List.of("SELECT", "INSERT", "UPDATE", "DELETE")
        ));

        List<String> violations = AlertSelfCheck.violations(env, db, false);

        assertThat(violations)
                .hasSize(2)
                .anyMatch(v -> v.contains("alert_event") && v.contains("INSERT"))
                .anyMatch(v -> v.contains("external_invocation_ledger") && v.contains("INSERT"));
    }

    @Test
    void rca_report_without_update_is_not_a_violation() {
        // BA-10②/G0-04：报告不可变是有意设计（V7 只授 SELECT/INSERT）——
        // 旧自检把它当普通业务表要求 UPDATE，真栈（按 V7 授权）启动必红。
        // 本用例在旧代码上红（误报 UPDATE 违规），修复后绿。
        Map<String, String> env = Map.of(
                "ALERTMANAGER_WEBHOOK_BEARER_TOKEN", "token",
                "HOLMES_API_KEY", "key"
        );
        var db = new FakeDbProbe(Map.of(
                "alert_inbox", List.of("SELECT", "INSERT", "UPDATE"),
                "alert_event", List.of("SELECT", "INSERT"),
                "incident", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_run", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_task", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_attempt", List.of("SELECT", "INSERT", "UPDATE"),
                "rca_report", List.of("SELECT", "INSERT"), // 与 V7 授权一致：无 UPDATE
                "external_invocation_ledger", List.of("SELECT", "INSERT"),
                "scheduler_slot", List.of("SELECT", "UPDATE")
        ));

        List<String> violations = AlertSelfCheck.violations(env, db, true);

        assertThat(violations).isEmpty();
    }

    private static AlertSelfCheck.DbPrivilegeProbe fakeDbWithFullPrivileges() {
        return new FakeDbProbe(Map.of(
                "alert_inbox", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "alert_event", List.of("SELECT", "INSERT"),
                "incident", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_run", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_task", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_attempt", List.of("SELECT", "INSERT", "UPDATE", "DELETE"),
                "rca_report", List.of("SELECT", "INSERT"),
                "external_invocation_ledger", List.of("SELECT", "INSERT"),
                "scheduler_slot", List.of("SELECT", "INSERT", "UPDATE", "DELETE")
        ));
    }

    private static class FakeDbProbe implements AlertSelfCheck.DbPrivilegeProbe {
        private final Map<String, List<String>> privileges;

        FakeDbProbe(Map<String, List<String>> privileges) {
            this.privileges = privileges;
        }

        @Override
        public boolean hasTablePrivilege(String tableName, String privilege) {
            return privileges.getOrDefault(tableName, List.of()).contains(privilege);
        }
    }
}

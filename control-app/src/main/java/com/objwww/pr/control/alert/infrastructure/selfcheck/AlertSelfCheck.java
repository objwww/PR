package com.objwww.pr.control.alert.infrastructure.selfcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 告警域启动自检（AM1-T09）。
 *
 * <p>检查项：
 * <ol>
 *   <li>Alertmanager webhook bearer token 存在；</li>
 *   <li>V7 九表权限（alert_inbox/event/incident/rca_run/task/attempt/report/external_invocation_ledger/scheduler_slot）；</li>
 * </ol>
 *
 * <p>历史第 2 项「HolmesGPT 凭证存在（如启用）」随 M6-07 Holmes 退场摘除——
 * 第二引擎无生产入口后，HOLMES_API_KEY 不再是启动前置（.env 键已回收）。
 *
 * <p>探针接口由调用者提供，零 Spring 依赖。
 */
public final class AlertSelfCheck {

    public static final String WEBHOOK_BEARER_TOKEN_ENV = "ALERTMANAGER_WEBHOOK_BEARER_TOKEN";

    private static final String[] V7_TABLES = {
        "alert_inbox",
        "alert_event",
        "incident",
        "rca_run",
        "rca_task",
        "rca_attempt",
        "rca_report",
        "external_invocation_ledger",
        "scheduler_slot"
    };

    private AlertSelfCheck() {
    }

    /**
     * 告警域自检。
     *
     * @param env 环境变量探针
     * @param db  DB 权限探针
     * @return 违规列表（空 = 通过）
     */
    public static List<String> violations(
            Map<String, String> env,
            DbPrivilegeProbe db
    ) {
        List<String> violations = new ArrayList<>();

        // 1. Webhook bearer token
        String webhookToken = env.get(WEBHOOK_BEARER_TOKEN_ENV);
        if (webhookToken == null || webhookToken.isBlank()) {
            violations.add("缺少 Alertmanager webhook bearer token 环境变量 "
                    + WEBHOOK_BEARER_TOKEN_ENV
                    + "（AM1 入口验签必需）");
        }

        // 2. V7 九表权限（与 V7 实际授权对齐）：
        //    - alert_event / external_invocation_ledger / rca_report: INSERT + SELECT
        //      （只增不改；rca_report 报告不可变是有意设计——修订/发布走独立 publication 表，
        //      架构 AA-16 / AM3 v3.0 §6.2，BA-10②/G0-04）
        //    - scheduler_slot: SELECT + UPDATE（租约翻转，不允许增删行）
        //    - 其余 5 表: SELECT + INSERT + UPDATE（无 DELETE，防误删历史）
        for (String table : V7_TABLES) {
            boolean appendOnly = "alert_event".equals(table)
                    || "external_invocation_ledger".equals(table)
                    || "rca_report".equals(table);
            if (appendOnly) {
                // 只增不改表（含不可变报告）
                if (!db.hasTablePrivilege(table, "INSERT")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 INSERT 权限（V7 授权配错）");
                }
                if (!db.hasTablePrivilege(table, "SELECT")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 SELECT 权限（V7 授权配错）");
                }
            } else if ("scheduler_slot".equals(table)) {
                // 固定槽位表：只允许租约翻转
                if (!db.hasTablePrivilege(table, "SELECT")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 SELECT 权限（V7 授权配错）");
                }
                if (!db.hasTablePrivilege(table, "UPDATE")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 UPDATE 权限（V7 授权配错）");
                }
            } else {
                // 其他 6 表：SELECT + INSERT + UPDATE（无 DELETE）
                if (!db.hasTablePrivilege(table, "SELECT")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 SELECT 权限（V7 授权配错）");
                }
                if (!db.hasTablePrivilege(table, "INSERT")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 INSERT 权限（V7 授权配错）");
                }
                if (!db.hasTablePrivilege(table, "UPDATE")) {
                    violations.add("control DB 角色对 " + table
                            + " 无 UPDATE 权限（V7 授权配错）");
                }
            }
        }

        return violations;
    }

    /**
     * DB 权限探针接口（与 ControlSelfCheck 共用）。
     */
    public interface DbPrivilegeProbe {
        boolean hasTablePrivilege(String tableName, String privilege);
    }
}

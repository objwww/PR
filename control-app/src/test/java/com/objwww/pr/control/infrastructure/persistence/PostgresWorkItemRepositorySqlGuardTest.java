package com.objwww.pr.control.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I17 防回归守卫（INC-30/TB-07）：纯文本断言 PostgresWorkItemRepository 的六条
 * 租约/过期 SQL 常量——比较与 updated_at/lease_until 写入一律走 DB now()/make_interval，
 * 不出现应用时钟参数 :now（一旦出现即编译产物回退，本测试拦截）。
 */
class PostgresWorkItemRepositorySqlGuardTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresWorkItemRepository.java");

    private static final List<String> LEASE_SQL_CONSTANTS = List.of(
            "CLAIM_SQL", "HEARTBEAT_SQL", "EXPIRED_LEASES_SQL",
            "RECLAIM_SQL", "LEASE_GUARDED_UPDATE_SQL", "CANCEL_ACTIVE_SQL");

    private static final Pattern UPDATED_AT_DB_NOW = Pattern.compile("updated_at\\s*=\\s*now\\(\\)");
    private static final Pattern DB_INTERVAL = Pattern.compile("now\\(\\)\\s*\\+\\s*make_interval\\(secs =>");

    private static String sqlConstant(String source, String name) {
        String marker = "private static final String " + name + " = \"\"\"";
        int start = source.indexOf(marker);
        assertThat(start).as("SQL 常量存在: %s", name).isGreaterThanOrEqualTo(0);
        int end = source.indexOf("\"\"\"", start + marker.length());
        assertThat(end).as("SQL 常量闭合: %s", name).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void leaseSqlUsesDbClockAndNeverAppNowParam() throws IOException {
        String source = Files.readString(SOURCE);

        for (String name : LEASE_SQL_CONSTANTS) {
            String sql = sqlConstant(source, name);
            assertThat(sql).as("%s 不得引用应用时钟参数 :now", name).doesNotContain(":now");
        }

        String claim = sqlConstant(source, "CLAIM_SQL");
        assertThat(claim).as("CLAIM_SQL 租约窗口由 DB 时钟计算")
                .containsPattern(DB_INTERVAL);
        assertThat(claim).as("CLAIM_SQL available_at 比较走 DB now()")
                .contains("available_at <= now()");
        assertThat(claim).as("CLAIM_SQL updated_at 走 DB now()")
                .containsPattern(UPDATED_AT_DB_NOW);

        String heartbeat = sqlConstant(source, "HEARTBEAT_SQL");
        assertThat(heartbeat).as("HEARTBEAT_SQL 续租窗口由 DB 时钟计算")
                .containsPattern(DB_INTERVAL);
        assertThat(heartbeat).as("HEARTBEAT_SQL updated_at 走 DB now()")
                .containsPattern(UPDATED_AT_DB_NOW);

        assertThat(sqlConstant(source, "EXPIRED_LEASES_SQL"))
                .as("EXPIRED_LEASES_SQL 过期比较走 DB now()")
                .contains("lease_until < now()");

        String reclaim = sqlConstant(source, "RECLAIM_SQL");
        assertThat(reclaim).as("RECLAIM_SQL 过期比较走 DB now()")
                .contains("lease_until < now()");
        assertThat(reclaim).as("RECLAIM_SQL updated_at 走 DB now()")
                .containsPattern(UPDATED_AT_DB_NOW);

        assertThat(sqlConstant(source, "LEASE_GUARDED_UPDATE_SQL"))
                .as("LEASE_GUARDED_UPDATE_SQL updated_at 走 DB now()")
                .containsPattern(UPDATED_AT_DB_NOW);

        assertThat(sqlConstant(source, "CANCEL_ACTIVE_SQL"))
                .as("CANCEL_ACTIVE_SQL updated_at 走 DB now()")
                .containsPattern(UPDATED_AT_DB_NOW);
    }
}

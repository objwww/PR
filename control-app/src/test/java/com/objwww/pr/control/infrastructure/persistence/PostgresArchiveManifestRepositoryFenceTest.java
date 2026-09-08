package com.objwww.pr.control.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * archive_manifest state 单向栅栏的仓库面（M5-19，INV-AM5-19 恰一次栅栏）：
 * 仅 EXPORTED→VERIFIED→ARCHIVED 可推进；倒退/跳级一律 false 且<b>不触库</b>。
 * BA-43（195 真 PG 实证）：裸 WHERE state=:from 会放行 ARCHIVED→EXPORTED 倒退——
 * 恰一次栅栏只在调用面闭合不够，仓库面必须自持白名单。懒池（无 jdbcUrl）可证
 * 「非法组合零 SQL」：一旦回归触库即 Hikari 配置错红。
 */
class PostgresArchiveManifestRepositoryFenceTest {

    private final PostgresArchiveManifestRepository repo =
            new PostgresArchiveManifestRepository(
                    JdbcClient.create(new HikariDataSource()));

    @Test
    void backwardsAndSkippingTransitionsAreRefusedWithoutTouchingDb() {
        assertThat(repo.advanceState("p", "ARCHIVED", "EXPORTED"))
                .as("终态不可倒退").isFalse();
        assertThat(repo.advanceState("p", "VERIFIED", "EXPORTED"))
                .as("校验态不可倒退").isFalse();
        assertThat(repo.advanceState("p", "EXPORTED", "ARCHIVED"))
                .as("禁止跳过 VERIFIED").isFalse();
        assertThat(repo.advanceState("p", "ARCHIVED", "ARCHIVED"))
                .as("同态重放非法").isFalse();
    }
}

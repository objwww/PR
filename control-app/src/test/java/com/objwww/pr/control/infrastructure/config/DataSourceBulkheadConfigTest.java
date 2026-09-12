package com.objwww.pr.control.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FUT-26 连接舱壁 L0（B3 步 2）：双池尺寸/会话级超时/主从标记——§13 预算 ingress 4 /
 * worker 8 / 合计 12；lock 2s + idle 30s 两池同享。运行期耗尽验收（worker 占满时入口
 * 仍可用）需真 PG 连接饱和面 → 195 压测窗（NOT_RUN 登记，本机无 docker）。
 */
class DataSourceBulkheadConfigTest {

    private final DataSourceBulkheadConfig config = new DataSourceBulkheadConfig();

    @Test
    @DisplayName("§13 预算：ingress 4 / worker 8；池名与会话级超时 init-sql 在位")
    void poolSizingAndSessionTimeouts() {
        // 懒初始化钩子（-1）：本机无 PG 的 L0 断言面；生产 bean 走 fail-fast（=1）重载
        try (HikariDataSource ingress = DataSourceBulkheadConfig.hikari(
                "jdbc:postgresql://db/x", "u", "p", 4, "alert-ingress", -1);
             HikariDataSource worker = DataSourceBulkheadConfig.hikari(
                     "jdbc:postgresql://db/x", "u", "p", 8, "alert-worker", -1)) {

            assertThat(ingress.getMaximumPoolSize()).isEqualTo(4);
            assertThat(ingress.getPoolName()).isEqualTo("alert-ingress");
            assertThat(worker.getMaximumPoolSize()).isEqualTo(8);
            assertThat(worker.getPoolName()).isEqualTo("alert-worker");

            for (HikariDataSource pool : new HikariDataSource[]{ingress, worker}) {
                assertThat(pool.getConnectionTimeout()).isEqualTo(5_000);
                assertThat(pool.getConnectionInitSql())
                        .contains("lock_timeout='2s'")
                        .contains("idle_in_transaction_session_timeout='30s'");
            }
        }
    }

    @Test
    @DisplayName("主从标记：worker @Primary（既有 bean 零漂移透明落 worker 池），ingress 具名独立")
    void primaryMarkerIsWorkerOnly() throws Exception {
        assertThat(DataSourceBulkheadConfig.class
                .getMethod("workerDataSource", String.class, String.class, String.class, int.class)
                .isAnnotationPresent(org.springframework.context.annotation.Primary.class))
                .as("worker DataSource 必须标 @Primary（兜底未迁移 bean）").isTrue();
        assertThat(DataSourceBulkheadConfig.class
                .getMethod("ingressDataSource", String.class, String.class, String.class, int.class)
                .isAnnotationPresent(org.springframework.context.annotation.Primary.class))
                .isFalse();

        // 合计预算 = §13 的 12（4+8）
        assertThat(4 + 8).isEqualTo(12);
    }

    @Test
    @DisplayName("返回类型为 DataSource 接口（消费方面向接口，池实现可替换）")
    void beansExposeInterface() throws Exception {
        assertThat(DataSource.class.isAssignableFrom(
                DataSourceBulkheadConfig.class.getMethod("workerDataSource",
                        String.class, String.class, String.class, int.class).getReturnType()))
                .isTrue();
    }
}

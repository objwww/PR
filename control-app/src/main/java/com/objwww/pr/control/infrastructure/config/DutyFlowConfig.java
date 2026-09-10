package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.infrastructure.persistence.PostgresDutyStore;
import com.objwww.pr.control.ops.duty.application.DutyDispatchService;
import com.objwww.pr.control.ops.duty.application.DutyFallbackWatcher;
import com.objwww.pr.control.ops.duty.domain.DutyStore;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;

/**
 * 值班流装配（M7-13；docker profile 手工装配，惯例沿 AlertFlowConfig）。
 *
 * <p>B-41 律：派发与 watcher 的时钟一律 {@code Instant::now}（Supplier）——
 * 传 Instant 值会把 bean 创建时刻冻结成永恒"现在"（sent_at 撒谎/退避失效/期限闸失效）。
 * 期限默认 24h（契约③ 最长通知期限：attempt 预算外的时间闸，与 notify-app
 * FencedNotifyExecutor 的 outbox 期限同值对齐）。
 */
@Configuration
@Profile("docker")
public class DutyFlowConfig {

    /**
     * 单实例双端口（B-53 同律实证：第二实例同型 bean 致 DutyAdminController 注入
     * 歧义，docker 装配面本地 UT 不可见，195 真启动才炸）——PostgresDutyStore 同时
     * 实现 DutyStore/DutyAdminStore，一个 bean 供两端口消费，零歧义。
     */
    @Bean
    public PostgresDutyStore dutyStore(JdbcClient jdbc, TransactionOperations tx) {
        return new PostgresDutyStore(jdbc, tx);
    }

    @Bean
    public DutyDispatchService dutyDispatchService(DutyStore dutyStore) {
        return new DutyDispatchService(dutyStore, Instant::now);
    }

    @Bean
    public DutyFallbackWatcher dutyFallbackWatcher(
            DutyStore dutyStore,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.duty.max-notification-age:PT24H}") Duration maxAge,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.duty.degrade-scan-interval:PT30S}") Duration scanInterval) {
        return new DutyFallbackWatcher(dutyStore, Instant::now, maxAge, scanInterval, 10_000L);
    }

    /** 降级扫描循环随容器启停（AlertFlowConfig.alertFlowLifecycle 同款 SmartLifecycle） */
    @Bean
    public SmartLifecycle dutyWatcherLifecycle(DutyFallbackWatcher watcher) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                watcher.start();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                watcher.stop();
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}

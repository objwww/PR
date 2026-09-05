package com.objwww.pr.arenaadmin;

import com.objwww.pr.arenaadmin.application.ChaosActivationService;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
/**
 * docker profile 装配：chaos 域唯一写者的手工接线（零注解仓储 + 显式 bean）。
 * reaper 循环 = 单虚拟线程（TTL 过期清扫 + 恢复收口 + 启动孤儿清扫），随上下文启停。
 */
@Configuration
@Profile("docker")
public class ChaosAdminConfig {

    @Bean
    public JdbcClient chaosAdminJdbcClient(javax.sql.DataSource ds) {
        return JdbcClient.create(ds);
    }

    @Bean
    public PostgresChaosAdminStore chaosAdminStore(JdbcClient jdbc,
                                                   PlatformTransactionManager tm) {
        return new PostgresChaosAdminStore(jdbc, new TransactionTemplate(tm));
    }

    @Bean
    public ChaosActivationService chaosActivationService(
            PostgresChaosAdminStore store,
            @Value("${app.chaos-admin.ttl-min-seconds:30}") int ttlMin,
            @Value("${app.chaos-admin.ttl-max-seconds:7200}") int ttlMax) {
        return new ChaosActivationService(store, ttlMin, ttlMax);
    }

    @Bean
    public SmartLifecycle chaosReaperLifecycle(ChaosActivationService service,
                                               @Value("${app.chaos-admin.reaper-interval-ms:5000}")
                                               long intervalMs) {
        return new SmartLifecycle() {
            private Thread thread;
            private volatile boolean running;

            @Override
            public synchronized void start() {
                running = true;
                thread = Thread.ofVirtual().name("chaos-reaper").start(() -> {
                    while (running) {
                        try {
                            Thread.sleep(intervalMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (!running) {
                            return;
                        }
                        try {
                            service.reaperTick();
                        } catch (RuntimeException e) {
                            // 单轮失败继续（DB 短暂不可用不杀清扫循环）
                        }
                    }
                });
            }

            @Override
            public synchronized void stop() {
                running = false;
                if (thread != null) {
                    thread.interrupt();
                    try {
                        thread.join(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 1;
            }
        };
    }
}

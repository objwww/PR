package com.objwww.pr.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sandbox Broker 主应用（M4 §4.2 独立进程）。
 *
 * <p>架构要点（D1/D17/R1）：
 * <ul>
 *   <li>独立进程：与 control-app 解耦，零共享 JVM</li>
 *   <li>零 DB 直连：只通过 Control HTTP API 交互（claim/heartbeat/report）</li>
 *   <li>Docker 容器执行：通过 docker.sock spawn 兄弟容器</li>
 *   <li>物料提取：从 Artifact 存储拉取 workspace tar.gz，SafeTarExtractor 安全解包</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class BrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }
}

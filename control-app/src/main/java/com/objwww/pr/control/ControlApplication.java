package com.objwww.pr.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Control 进程入口（AM1 起为告警控制面：Alertmanager webhook 接收 → Incident 聚合 → RCA 调度）。
 * 默认 profile 无数据库可空跑；真实数据源见 application-docker.yml。
 */
@SpringBootApplication
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}

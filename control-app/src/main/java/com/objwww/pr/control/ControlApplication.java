package com.objwww.pr.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Control 进程入口。本进程物理上拿不到 GitHub 写凭证（F1-A/F1-B）。
 * 默认 profile 无数据库可空跑；真实数据源见 application-docker.yml。
 */
@SpringBootApplication
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}

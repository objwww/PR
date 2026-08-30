package com.objwww.pr.publisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Publisher 进程入口。独占 GitHub 写凭证，唯一写出口（GitHubWriteAdapter）。
 * 默认 profile 无数据库可空跑；真实数据源见 application-docker.yml。
 */
@SpringBootApplication
public class PublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublisherApplication.class, args);
    }
}

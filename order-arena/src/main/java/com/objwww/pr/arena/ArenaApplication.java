package com.objwww.pr.arena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * order-arena 进程入口（AM2 订单靶场，docs/告警AM2-落码技术方案.md v2.0 M2-01）。
 * 默认 profile 无数据库可空跑；真实数据源走 docker profile（arena_app 角色，
 * Hikari 上限 8，§7.2 线程与资源预算）。
 */
@SpringBootApplication
public class ArenaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArenaApplication.class, args);
    }
}

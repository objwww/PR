package com.objwww.pr.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * notify-app 启动（AM3 §3.2）：单 worker 常驻（领取循环经 NotifyConfig
 * initMethod 启动）；web 端口仅供 compose 健康检查（/health），无业务端点。
 */
@SpringBootApplication
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}

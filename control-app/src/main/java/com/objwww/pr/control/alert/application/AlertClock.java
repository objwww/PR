package com.objwww.pr.control.alert.application;

import java.time.Instant;

/**
 * 告警流时钟抽象（入口/投影/消费循环共用；测试注入固定时钟，生产 system）。
 */
public interface AlertClock {

    Instant now();

    static AlertClock system() {
        return Instant::now;
    }
}

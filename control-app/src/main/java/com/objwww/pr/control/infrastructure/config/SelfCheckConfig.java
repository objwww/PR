package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.infrastructure.selfcheck.ControlSelfCheck;
import com.objwww.pr.control.infrastructure.selfcheck.DbPrivilegeProbe;
import com.objwww.pr.control.infrastructure.selfcheck.EnvironmentProbe;
import com.objwww.pr.control.infrastructure.selfcheck.StartupSelfCheckRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 启动自检接线（B25，仅 docker profile；默认 profile 无 DataSource，自检无从执行也不装配，
 * 空跑不破）。自检失败抛异常 → Spring Boot 拒绝启动（DP-02/DP-03 的运行时门）。
 */
@Configuration
@Profile("docker")
public class SelfCheckConfig {

    @Bean
    public StartupSelfCheckRunner controlStartupSelfCheck(JdbcClient jdbc) {
        EnvironmentProbe env = EnvironmentProbe.system();
        DbPrivilegeProbe db = DbPrivilegeProbe.postgres(jdbc);
        return new StartupSelfCheckRunner("control", () -> ControlSelfCheck.violations(env.all(), db));
    }
}

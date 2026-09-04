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

    /**
     * holmesEnabled（BA-10① 收尾）：默认 true——AM1 的 RCA 执行面就是 Holmes，
     * 凭证缺失必须在启动时 fail-closed，而不是等第一次调查 401。
     * 显式置 app.alert.holmes.enabled=false 可关闭该检查（如纯投影调试部署）。
     */
    @Bean
    public StartupSelfCheckRunner controlStartupSelfCheck(
            JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.alert.holmes.enabled:true}") boolean holmesEnabled) {
        EnvironmentProbe env = EnvironmentProbe.system();
        DbPrivilegeProbe db = DbPrivilegeProbe.postgres(jdbc);
        return new StartupSelfCheckRunner("control",
                () -> ControlSelfCheck.violations(env.all(), db, holmesEnabled));
    }
}

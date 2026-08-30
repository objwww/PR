package com.objwww.pr.publisher.infrastructure.config;

import com.objwww.pr.publisher.infrastructure.selfcheck.DbPrivilegeProbe;
import com.objwww.pr.publisher.infrastructure.selfcheck.EnvironmentProbe;
import com.objwww.pr.publisher.infrastructure.selfcheck.FileSecurityProbe;
import com.objwww.pr.publisher.infrastructure.selfcheck.ProcessProbe;
import com.objwww.pr.publisher.infrastructure.selfcheck.PublisherSelfCheck;
import com.objwww.pr.publisher.infrastructure.selfcheck.StartupSelfCheckRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 启动自检接线（B25，仅 docker profile；惯例同 PublisherWiringConfig）。
 * 私钥只读/非 root/无模型 key/DB 权限矩阵任一不满足即拒绝启动（DP-04 的运行时门）。
 */
@Configuration
@Profile("docker")
public class SelfCheckConfig {

    @Bean
    public StartupSelfCheckRunner publisherStartupSelfCheck(
            JdbcClient jdbc,
            @Value("${publisher.github.private-key-path:}") String privateKeyPath) {
        EnvironmentProbe env = EnvironmentProbe.system();
        DbPrivilegeProbe db = DbPrivilegeProbe.postgres(jdbc);
        ProcessProbe process = ProcessProbe.current();
        FileSecurityProbe files = FileSecurityProbe.system();
        return new StartupSelfCheckRunner("publisher",
                () -> PublisherSelfCheck.violations(env.all(), db, process, files, privateKeyPath));
    }
}

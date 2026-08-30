package com.objwww.pr.publisher.infrastructure.selfcheck;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 启动自检执行器薄壳（B25，仅 docker profile 装配——见 SelfCheckConfig）：
 * 任一违规即抛异常，ApplicationContext 起来就死（fail-closed，E5）。
 * 判定逻辑在 {@link PublisherSelfCheck}（纯逻辑，探针可注入），违规文案永不含凭证值。
 * 通过时落一行成功日志：DP-01 的部署验证以"启动自检通过"字样为凭据。
 */
public class StartupSelfCheckRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheckRunner.class);

    private final String scope;
    private final Supplier<List<String>> violations;

    public StartupSelfCheckRunner(String scope, Supplier<List<String>> violations) {
        this.scope = scope;
        this.violations = violations;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> found = violations.get();
        if (!found.isEmpty()) {
            throw new IllegalStateException("[" + scope + "] 启动自检失败（B25 运行时门）：\n - "
                    + String.join("\n - ", found));
        }
        log.info("[{}] 启动自检通过（B25 运行时门）", scope);
    }
}

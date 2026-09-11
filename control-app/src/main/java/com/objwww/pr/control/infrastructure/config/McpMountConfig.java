package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.mcp.McpMountManager;
import com.objwww.pr.control.alert.application.mcp.McpToolInvoker;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.interfaces.McpMountController;
import com.objwww.pr.control.infrastructure.mcp.SdkMcpServerClientFactory;
import com.objwww.pr.control.infrastructure.persistence.PostgresMcpServerRegistryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * EN-06 MCP 动态挂载装配。fail-closed 默认关：{@code app.alert.mcp.mount-enabled=true}
 * 才装配注册表/管理面/控制器（缺省上下文零 MCP 面——机制测试与全量回归不受真网络
 * 面影响；上线经部署配置显式开启）。M12：ApplicationRunner 启动时从 PG 恢复快照
 * （enabled 重校验挂载、disabled 保持 dormant、失败不阻断进程——行级失败落 FAILED 状态）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.alert.mcp", name = "mount-enabled", havingValue = "true")
public class McpMountConfig {

    @Bean
    public McpServerRegistryRepository mcpServerRegistryRepository(JdbcClient jdbc) {
        return new PostgresMcpServerRegistryRepository(jdbc);
    }

    @Bean
    public McpMountManager mcpMountManager(
            McpServerRegistryRepository registryRepository,
            @Value("${app.alert.mcp.schema-ttl-seconds:300}") long schemaTtlSeconds,
            @Value("${app.alert.mcp.revalidation-attempts:2}") int revalidationAttempts,
            @Value("${app.alert.mcp.stdio-command-allowlist:}") String stdioCommandAllowlist) {
        Set<String> allowlist = Arrays.stream(stdioCommandAllowlist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return new McpMountManager(registryRepository,
                new SdkMcpServerClientFactory(Duration.ofSeconds(10), Duration.ofSeconds(5)),
                Clock.systemUTC(), Duration.ofSeconds(schemaTtlSeconds),
                revalidationAttempts, allowlist);
    }

    @Bean
    public McpToolInvoker mcpToolInvoker(
            McpMountManager manager,
            RcaToolInvocationLedger ledger,
            @Value("${app.alert.mcp.max-result-bytes:262144}") int maxResultBytes) {
        return new McpToolInvoker(manager, ledger, maxResultBytes);
    }

    @Bean
    public McpMountController mcpMountController(McpMountManager manager) {
        return new McpMountController(manager);
    }

    @Bean
    public ApplicationRunner mcpMountRecovery(McpMountManager manager) {
        return args -> manager.recover();
    }
}

package com.objwww.pr.control.eval.infrastructure;

import com.objwww.pr.control.eval.application.WorkerSchemaFreshnessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.util.Objects;

/**
 * WorkerSchemaFreshnessGuard 实现：DB flyway 最大版本 vs 本进程 classpath 迁移面
 * （jar 内 {@code db/migration/V*__*.sql} 最大 V 号——镜像打包即冻结，旧镜像必然
 * 少迁移文件）。DB 领先 = 镜像陈旧 = stale。迁移版本号约定为纯整数（V142 式）。
 */
public class ClasspathFlywayFreshnessGuard implements WorkerSchemaFreshnessGuard {

    private static final Logger log = LoggerFactory.getLogger(
            ClasspathFlywayFreshnessGuard.class);

    private final JdbcClient jdbc;
    private final ResourcePatternResolver resources;

    public ClasspathFlywayFreshnessGuard(JdbcClient jdbc) {
        this(jdbc, new PathMatchingResourcePatternResolver());
    }

    ClasspathFlywayFreshnessGuard(JdbcClient jdbc, ResourcePatternResolver resources) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.resources = Objects.requireNonNull(resources);
    }

    @Override
    public boolean stale() {
        try {
            int dbMax = dbMaxVersion();
            int localMax = classpathMaxVersion();
            if (dbMax > localMax) {
                log.error("schema 新鲜度护栏：DB flyway 最大 V{} 领先本进程迁移面 V{}"
                        + "——镜像陈旧", dbMax, localMax);
                return true;
            }
            return false;
        } catch (RuntimeException | IOException e) {
            log.error("schema 新鲜度检查失败，fail-closed 按 stale 论: {}", e.getMessage());
            return true;
        }
    }

    /** DB 侧：已应用成功的最大整数版本（SystemHealthController 同源读面） */
    private int dbMaxVersion() {
        return jdbc.sql("select max(version::int) from flyway_schema_history"
                        + " where success and version ~ '^\\d+$'")
                .query(Integer.class).optional()
                .orElseThrow(() -> new IllegalStateException(
                        "flyway_schema_history 无可读整数版本行"));
    }

    /** 进程侧：classpath 内 V&lt;整数&gt;__*.sql 的最大号；扫不到 = 异常（fail-closed） */
    int classpathMaxVersion() throws IOException {
        int max = 0;
        for (Resource resource
                : resources.getResources("classpath*:db/migration/V*__*.sql")) {
            String name = resource.getFilename();
            if (name == null) {
                continue;
            }
            int end = name.indexOf("__");
            if (end <= 1) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(name.substring(1, end)));
            } catch (NumberFormatException ignored) {
                // 非整数版本号不参与比较（与 DB 侧 '^\d+$' 过滤同口径）
            }
        }
        if (max == 0) {
            throw new IllegalStateException(
                    "classpath 未扫到迁移文件（db/migration/V*__*.sql）");
        }
        return max;
    }
}

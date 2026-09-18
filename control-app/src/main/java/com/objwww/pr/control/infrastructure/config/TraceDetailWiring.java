package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.domain.repository.RcaToolSpanDetailReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolSpanDetailReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Trace 工具 span 明细读面装配（M-d T2）。
 *
 * <p>独立装配类而非并入 PersistenceConfig：该文件属主会话在途改动面，M-d 纪律
 * 禁触碰他人未提交文件——新读面以新文件自承载，后续批次可再归并（沿既有
 * config-wiring 惯例，纯类零注解）。
 */
@Configuration
public class TraceDetailWiring {

    @Bean
    public RcaToolSpanDetailReader rcaToolSpanDetailReader(JdbcClient jdbc) {
        return new PostgresRcaToolSpanDetailReader(jdbc);
    }
}

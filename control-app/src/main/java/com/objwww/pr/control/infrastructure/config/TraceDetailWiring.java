package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.domain.repository.RcaToolSpanDetailReader;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolSpanDetailReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Trace 工具 span 明细读面装配（M-d T2）。
 *
 * <p>独立装配类而非并入 PersistenceConfig：该文件属主会话在途改动面，M-d 纪律
 * 禁触碰他人未提交文件——新读面以新文件自承载，后续批次可再归并（沿既有
 * config-wiring 惯例，纯类零注解）。
 *
 * <p>P6 续修（2026-09-19）：补 @Profile({"docker","eval"})——该装配强依赖
 * JdbcClient，无 profile 裸上下文（SecurityConfigTest/ControlContextSmokeTest）
 * 装载即炸 26 例；消费面 TraceDetailController 同为 docker profile，eval worker
 * 保留同 Bean 以防 eval 侧读面复用。
 */
@Configuration
@Profile({"docker", "eval"})
public class TraceDetailWiring {

    @Bean
    public RcaToolSpanDetailReader rcaToolSpanDetailReader(JdbcClient jdbc) {
        return new PostgresRcaToolSpanDetailReader(jdbc);
    }
}

package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.ReportQueryService;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaReportReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 报告 tab 读面接线（仅 docker profile；W3 OpsMetricsConfig 同式独立装配，
 * 不动 PersistenceConfig）。
 *
 * <p>rca_report 读端口新立 {@link RcaReportReader}（显式授权列、不读 raw_text）；
 * 写面 rcaReportRepository / reportPublicationRepository 沿用 PersistenceConfig
 * 既有 bean，本类只消费不重复注册。
 */
@Configuration
@Profile("docker")
public class ReportQueryConfig {

    @Bean
    public RcaReportReader rcaReportReader(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new PostgresRcaReportReader(jdbc, objectMapper);
    }

    @Bean
    public ReportQueryService reportQueryService(RcaReportReader rcaReportReader,
            ReportPublicationRepository reportPublicationRepository,
            ObjectMapper objectMapper) {
        return new ReportQueryService(rcaReportReader, reportPublicationRepository, objectMapper);
    }
}

package com.objwww.pr.broker.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Broker 配置（M4 §4.2 Docker 客户端 + Control API 客户端）。
 *
 * <p>docker-java 3.7.1 + httpclient5 + api.version=1.44（方案 §4.6）。
 */
@Configuration
public class BrokerConfig {

    @Value("${broker.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${broker.docker.api-version:1.44}")
    private String dockerApiVersion;

    @Value("${broker.control.base-url:http://localhost:8080}")
    private String controlBaseUrl;

    @Value("${broker.worker-id:broker-001}")
    private String workerId;

    /**
     * Docker 客户端（目标配置：docker-java 3.7.1 + httpclient5 + api 1.44，待 T00 在 195 实证）。
     */
    @Bean
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withApiVersion(dockerApiVersion)
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        return DockerClientImpl.getInstance(config, httpClient);
    }

    @Bean
    public String controlBaseUrl() {
        return controlBaseUrl;
    }

    @Bean
    public String workerId() {
        return workerId;
    }
}

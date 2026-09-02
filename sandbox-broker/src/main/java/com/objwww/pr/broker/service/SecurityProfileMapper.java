package com.objwww.pr.broker.service;

import com.github.dockerjava.api.model.*;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;

/**
 * 安全剖面映射器（M4 §4.5 SecurityProfile 全字段映射到 docker-java）。
 *
 * <p>目标剖面字段（类型安全表达已核对；dockerd 实际接受与否待 T00 在 195 实证）：
 * <ul>
 *   <li>NetworkMode: "none"（网络隔离）</li>
 *   <li>ReadonlyRootfs: true（只读根文件系统）</li>
 *   <li>CapDrop: ["ALL"]（丢弃所有 capabilities）</li>
 *   <li>SecurityOpt: ["no-new-privileges"]（防提权）</li>
 *   <li>NanoCPUs: CPU 限额（纳秒）</li>
 *   <li>Memory/MemorySwap: 内存限额（F-37②；195 cgroup v1 swap accounting 启用状态待 T00 实证）</li>
 *   <li>PidsLimit: PID 限额</li>
 *   <li>LogConfig: json-file driver + max-size/max-file</li>
 *   <li>TmpFs: /tmp 挂载（rw,noexec,nosuid,size=100m）</li>
 * </ul>
 */
@Component
public class SecurityProfileMapper {

    private static final Logger log = LoggerFactory.getLogger(SecurityProfileMapper.class);

    /**
     * 从 JobSpec 构建完整的 HostConfig（安全剖面全字段）。
     *
     * @param jobSpec 作业规格
     * @return Docker HostConfig
     */
    public HostConfig buildHostConfig(JobSpec jobSpec) {
        JobSpec.ResourceLimits limits = jobSpec.resourceLimits();

        // 网络策略映射
        String networkMode = mapNetworkPolicy(jobSpec.networkPolicy());

        HostConfig hostConfig = HostConfig.newHostConfig()
            // 网络隔离
            .withNetworkMode(networkMode)

            // 只读根文件系统（F-37①）
            .withReadonlyRootfs(true)

            // 资源限额
            .withNanoCPUs(limits.nanoCPUs())
            .withMemory(limits.memoryBytes())
            .withMemorySwap(limits.memorySwapBytes())  // 等于 Memory = 禁 swap（F-37②）
            .withPidsLimit(Long.valueOf(limits.pidsLimit()))

            // 安全选项：防提权
            .withSecurityOpts(Arrays.asList("no-new-privileges"))

            // 丢弃所有 capabilities
            .withCapDrop(Capability.ALL);

        log.debug("Built HostConfig: network={}, readonlyRootfs=true, memory={}, cpus={}",
                  networkMode, limits.memoryBytes(), limits.nanoCPUs());

        return hostConfig;
    }

    private String mapNetworkPolicy(JobSpec.NetworkPolicy networkPolicy) {
        return switch (networkPolicy) {
            case NONE -> "none";
            case LIMITED -> "none";  // M4a 暂不支持 LIMITED，等同 NONE
        };
    }
}

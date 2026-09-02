package com.objwww.pr.broker.service;

import com.github.dockerjava.api.model.Capability;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-74~78: SecurityProfileMapper 单元测试（§4.5 安全剖面全字段映射）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>UT-74: 网络策略映射（NONE/LIMITED → "none"）</li>
 *   <li>UT-75: 资源限额映射（CPU/Memory/MemorySwap/PIDs）</li>
 *   <li>UT-76: 安全选项映射（no-new-privileges）</li>
 *   <li>UT-77: Capability 映射（CapDrop ALL）</li>
 *   <li>UT-78: 只读根文件系统映射</li>
 * </ul>
 */
class SecurityProfileMapperTest {

    private SecurityProfileMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SecurityProfileMapper();
    }

    /**
     * UT-74: 网络策略 NONE 映射为 docker network mode "none"
     */
    @Test
    void buildHostConfig_networkPolicyNone_mapsToNone() {
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertEquals("none", hostConfig.getNetworkMode());
    }

    /**
     * UT-74: 网络策略 LIMITED 在 M4a 映射为 "none"（暂不支持 LIMITED）
     */
    @Test
    void buildHostConfig_networkPolicyLimited_mapsToNone() {
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.LIMITED);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertEquals("none", hostConfig.getNetworkMode());
    }

    /**
     * UT-75: 资源限额正确映射（CPU/Memory/MemorySwap/PIDs）
     */
    @Test
    void buildHostConfig_resourceLimits_mappedCorrectly() {
        JobSpec.ResourceLimits limits = new JobSpec.ResourceLimits(
            2_000_000_000L,       // 2 CPU cores
            1024 * 1024 * 1024L,  // 1 GB memory
            1024 * 1024 * 1024L,  // 1 GB swap (= memory → 禁 swap)
            200                   // 200 PIDs
        );
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE, limits);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertEquals(2_000_000_000L, hostConfig.getNanoCPUs());
        assertEquals(1024 * 1024 * 1024L, hostConfig.getMemory());
        assertEquals(1024 * 1024 * 1024L, hostConfig.getMemorySwap());
        assertEquals(200L, hostConfig.getPidsLimit());
    }

    /**
     * UT-75: MemorySwap = Memory 实现禁 swap（F-37②）
     */
    @Test
    void buildHostConfig_memorySwapEqualsMemory_disablesSwap() {
        JobSpec.ResourceLimits limits = new JobSpec.ResourceLimits(
            1_000_000_000L,
            512 * 1024 * 1024L,  // 512 MB
            512 * 1024 * 1024L,  // swap = memory
            100
        );
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE, limits);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertEquals(hostConfig.getMemory(), hostConfig.getMemorySwap(),
                     "MemorySwap 应等于 Memory 以禁用 swap");
    }

    /**
     * UT-76: 安全选项包含 "no-new-privileges"
     */
    @Test
    void buildHostConfig_securityOpts_containsNoNewPrivileges() {
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertNotNull(hostConfig.getSecurityOpts());
        assertTrue(hostConfig.getSecurityOpts().contains("no-new-privileges"),
                   "SecurityOpts 应包含 no-new-privileges");
    }

    /**
     * UT-77: CapDrop 包含 ALL（丢弃所有 capabilities）
     */
    @Test
    void buildHostConfig_capDrop_containsAll() {
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertNotNull(hostConfig.getCapDrop());
        Capability[] capDrop = hostConfig.getCapDrop();
        boolean containsAll = false;
        for (Capability cap : capDrop) {
            if (cap == Capability.ALL) {
                containsAll = true;
                break;
            }
        }
        assertTrue(containsAll, "CapDrop 应包含 ALL");
    }

    /**
     * UT-78: ReadonlyRootfs 为 true（F-37①）
     */
    @Test
    void buildHostConfig_readonlyRootfs_isTrue() {
        JobSpec jobSpec = createJobSpec(JobSpec.NetworkPolicy.NONE);

        var hostConfig = mapper.buildHostConfig(jobSpec);

        assertTrue(hostConfig.getReadonlyRootfs(),
                   "ReadonlyRootfs 应为 true");
    }

    /**
     * UT-74~78: null JobSpec 抛出 NullPointerException
     */
    @Test
    void buildHostConfig_nullJobSpec_throwsNPE() {
        assertThrows(NullPointerException.class, () -> {
            mapper.buildHostConfig(null);
        });
    }

    // ---- 辅助方法 ----

    private JobSpec createJobSpec(JobSpec.NetworkPolicy networkPolicy) {
        JobSpec.ResourceLimits limits = new JobSpec.ResourceLimits(
            1_000_000_000L,
            512 * 1024 * 1024L,
            512 * 1024 * 1024L,
            100
        );
        return createJobSpec(networkPolicy, limits);
    }

    private JobSpec createJobSpec(JobSpec.NetworkPolicy networkPolicy, JobSpec.ResourceLimits limits) {
        return new JobSpec(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0L,
            "REVIEW_TOOL_CALL",
            new JobSpec.ImageRef(new Digest("a".repeat(64)), "ubuntu:22.04"),
            List.of("echo", "test"),
            new Digest("b".repeat(64)),
            List.of(),
            limits,
            300,
            networkPolicy
        );
    }
}

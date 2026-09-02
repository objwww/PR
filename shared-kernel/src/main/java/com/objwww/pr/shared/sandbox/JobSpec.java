package com.objwww.pr.shared.sandbox;

import com.objwww.pr.shared.Digest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 沙箱作业完整规格（不可信执行单元的身份+配置，M4 §4.3 JobSpec）。
 *
 * <p>不含任何凭证（无 token、无 grant、无密钥）；模型不可提供镜像/命令/wrapper/工作目录。
 * 唯一铸造点：{@code SandboxJobSpecFactory}（domain 层，从 ToolCall + Run 上下文铸造）。
 */
public record JobSpec(
    UUID jobId,
    UUID attemptId,
    long leaseEpoch,
    String jobKind,                    // 工具类型枚举：REVIEW_TOOL_CALL / CODE_SEARCH 等
    ImageRef imageRef,                 // 镜像内容寻址引用（digest + tag）
    List<String> command,              // 固定 wrapper + 枚举参数（create 时定死，不接受模型直拼）
    Digest sourceSnapshotDigest,       // 源码快照 digest（workspace 物料之一）
    List<Digest> inputDigests,         // 其他输入 artifact digest（diff bundle / policy 等）
    ResourceLimits resourceLimits,     // CPU/内存/PID 限额
    int timeoutSeconds,                // 作业超时（秒）
    NetworkPolicy networkPolicy        // 网络策略（NONE / LIMITED）
) {
    public JobSpec {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(attemptId, "attemptId");
        if (leaseEpoch < 0) {
            throw new IllegalArgumentException("leaseEpoch < 0");
        }
        Objects.requireNonNull(jobKind, "jobKind");
        Objects.requireNonNull(imageRef, "imageRef");
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command empty");
        }
        Objects.requireNonNull(sourceSnapshotDigest, "sourceSnapshotDigest");
        Objects.requireNonNull(inputDigests, "inputDigests");
        Objects.requireNonNull(resourceLimits, "resourceLimits");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds <= 0");
        }
        Objects.requireNonNull(networkPolicy, "networkPolicy");

        command = List.copyOf(command);
        inputDigests = List.copyOf(inputDigests);
    }

    /** 镜像内容寻址引用（digest + 可选 tag） */
    public record ImageRef(Digest digest, String tag) {
        public ImageRef {
            Objects.requireNonNull(digest, "digest");
            // tag 可选（null 表示仅用 digest 引用）
        }

        public String toReference() {
            return tag != null ? tag + "@sha256:" + digest.hex() : "sha256:" + digest.hex();
        }
    }

    /** 资源限额（CPU/内存/PID） */
    public record ResourceLimits(
        long nanoCPUs,          // 纳秒 CPU（1.5 CPU = 1_500_000_000L）
        long memoryBytes,       // 内存字节
        long memorySwapBytes,   // swap 字节（等于 memoryBytes = 禁 swap，F-37②）
        int pidsLimit           // PID 限额
    ) {
        public ResourceLimits {
            if (nanoCPUs <= 0) {
                throw new IllegalArgumentException("nanoCPUs <= 0");
            }
            if (memoryBytes <= 0) {
                throw new IllegalArgumentException("memoryBytes <= 0");
            }
            if (memorySwapBytes < memoryBytes) {
                throw new IllegalArgumentException("memorySwapBytes < memoryBytes");
            }
            if (pidsLimit <= 0) {
                throw new IllegalArgumentException("pidsLimit <= 0");
            }
        }
    }

    /** 网络策略 */
    public enum NetworkPolicy {
        /** 完全隔离（--network none） */
        NONE,
        /** 受限出网（预留 M4-P2，当前等同 NONE） */
        LIMITED
    }
}

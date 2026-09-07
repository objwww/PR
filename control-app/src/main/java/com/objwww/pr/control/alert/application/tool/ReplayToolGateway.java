package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.tool.ActionDigest;
import com.objwww.pr.control.alert.domain.tool.ActionEnvelope;
import com.objwww.pr.control.alert.domain.tool.ToolArgsValidator;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * 回放专用网关（AM4 M4-32）：REPLAY_MOCK 精确匹配面——候选模型（M4-33 Replay
 * Runner）的工具出口。固定序与 {@link ToolGateway} 镜像：注册表解析（未注册
 * 工具/版本照常 UNKNOWN_TOOL 硬拒绝，回放模式不是注册纪律的旁路）→ schema 校验
 * （未声明字段硬拒绝，INVALID_ARGS 同纪律）→ 同 envelope canonical digest（与
 * 活执行网关逐字段一致，两侧 digest 可直接互认，UT 锚定）→ 回放账本查询。
 * 命中 → {@link ReplayKind#REPLAY_HIT} 返回录制字节；未命中 →
 * {@link ReplayKind#REPLAY_MISS} 显式结局。<b>结构上无执行器调用路径、无网络、
 * 无时钟</b>——MISS 绝不降级为活执行（Shadow 隔离纪律，M4-34/35 前置）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public final class ReplayToolGateway {

    private final ToolRegistry registry;
    private final ToolReplayStore store;

    public ReplayToolGateway(ToolRegistry registry, ToolReplayStore store) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** 回放结局：HIT（精确命中，返回录制字节）或 MISS（任一字段不同，显式未命中） */
    public record ReplayResult(ReplayKind kind, String actionDigest, byte[] body) {
        public enum ReplayKind { REPLAY_HIT, REPLAY_MISS }
    }

    /** 精确回放：同 digest 命中返回录制响应，否则 REPLAY_MISS（绝不活执行） */
    public ReplayResult invoke(ToolGateway.ToolInvocation invocation) {
        ToolRegistry.Registration registration = resolveAndValidate(invocation);
        String digest = digestOf(invocation, registration);
        return store.find(digest)
                .map(record -> new ReplayResult(ReplayResult.ReplayKind.REPLAY_HIT,
                        digest, record.response()))
                .orElseGet(() -> new ReplayResult(ReplayResult.ReplayKind.REPLAY_MISS,
                        digest, null));
    }

    /**
     * 记录一次活执行的响应（供 M4-33 Replay Runner 录制基线）：与 invoke 同路径
     * 校验并计算 digest；同 digest 异响应冲突拒绝（禁静默覆盖），同 digest 同响应
     * 幂等；超 resultLimit 拒绝（回放面不引入无上界数据）。
     *
     * @return 该调用的 action digest（录制映射持久化用）
     */
    public String record(ToolGateway.ToolInvocation invocation, byte[] body) {
        Objects.requireNonNull(body, "body");
        if (body.length == 0) {
            throw new IllegalArgumentException("回放记录响应不得为空");
        }
        ToolRegistry.Registration registration = resolveAndValidate(invocation);
        String digest = digestOf(invocation, registration);
        if (body.length > registration.definition().resultLimitBytes()) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: " + body.length + " > "
                            + registration.definition().resultLimitBytes());
        }
        Optional<ToolReplayStore.ReplayRecord> existing = store.find(digest);
        if (existing.isPresent()) {
            if (!Arrays.equals(existing.get().response(), body)) {
                throw new IllegalStateException("REPLAY_RECORD_CONFLICT 冲突: 同 action digest "
                        + "记录到不同响应（禁静默覆盖）: " + digest);
            }
            return digest; // 幂等
        }
        store.put(new ToolReplayStore.ReplayRecord(digest, invocation.toolName(),
                invocation.toolVersion(), body));
        return digest;
    }

    /** 与活网关镜像的注册解析 + schema 硬拒绝（回放模式不是纪律旁路） */
    private ToolRegistry.Registration resolveAndValidate(ToolGateway.ToolInvocation invocation) {
        ToolRegistry.Registration registration = registry
                .find(invocation.toolName(), invocation.toolVersion())
                .orElseThrow(() -> new ToolControlPlaneException(ToolControlReason.UNKNOWN_TOOL,
                        "UNKNOWN_TOOL: " + invocation.toolName() + "@"
                                + invocation.toolVersion()));
        try {
            ToolArgsValidator.validate(registration.definition(), invocation.args());
        } catch (IllegalArgumentException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS, e.getMessage());
        }
        return registration;
    }

    /** 与活网关逐字段一致的 envelope canonical digest（两侧互认锚点，UT 锚定） */
    private String digestOf(ToolGateway.ToolInvocation invocation,
            ToolRegistry.Registration registration) {
        return ActionDigest.of(new ActionEnvelope("rca", invocation.toolName(),
                invocation.toolVersion(), registration.definition().schemaHash(),
                invocation.args(), invocation.timeRange(), invocation.inputSnapshotDigest()));
    }
}

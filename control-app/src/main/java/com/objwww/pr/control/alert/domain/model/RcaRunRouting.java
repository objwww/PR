package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.util.Objects;

/**
 * Run 铸造点的路由决策四列（M5-10；V25 rca_run.engine/config_digest/
 * stickiness_key/canary_bucket 的域面投影）。Run 启动固定不再变（方案 §4.1：
 * 在途 Run 不换 engine digest——回滚只影响新 Run）。
 *
 * <p>engine=NATIVE 时 configDigest 必带（候选桶必踩某版 bundle）；stickinessKey
 * 为归一化后键（CanaryBucketer.normalizedKey 产物）；bucket 为无模偏公式桶位
 * （NATIVE 意愿被降级/停放量时照记随审计）。
 */
public record RcaRunRouting(RcaEngine engine,
                            Digest configDigest,
                            String stickinessKey,
                            Integer bucket,
                            String decision) {

    public RcaRunRouting {
        Objects.requireNonNull(engine, "engine 不得为 null");
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision 不得为 blank（决策可回溯）");
        }
        if (engine == RcaEngine.NATIVE && configDigest == null) {
            throw new IllegalArgumentException("NATIVE 路由必带 configDigest（Run 启动固定）");
        }
    }

    /** HOLMES 主路径投影（无 bundle/拒绝放量等场景：路由四列除决策外全空或仅桶位） */
    public static RcaRunRouting holmes(Digest configDigest, String stickinessKey,
                                       Integer bucket, String decision) {
        return new RcaRunRouting(RcaEngine.HOLMES, configDigest, stickinessKey, bucket, decision);
    }
}

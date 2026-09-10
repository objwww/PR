package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.AgentProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 受审查运行器目录（R7-X2，v2.1 §十一.1 "RoleRegistry 绑定已发布 Profile+已部署
 * 运行器"）：runtime_kind → 运行器唯一映射，装配期去重快fail；解析期未部署类型
 * 显式拒绝（CAPABILITY_UNAVAILABLE，零远端零触网），不选"最接近"的运行器顶替。
 */
public final class RunnerDirectory {

    private final Map<String, RoleRunner> byKind = new LinkedHashMap<>();

    public RunnerDirectory(List<RoleRunner> runners) {
        Objects.requireNonNull(runners, "runners");
        for (RoleRunner runner : runners) {
            Objects.requireNonNull(runner, "runner");
            if (byKind.put(runner.runtimeKind(), runner) != null) {
                throw new IllegalStateException(
                        "运行器目录重复注册 runtime_kind: " + runner.runtimeKind());
            }
        }
    }

    /** 按 Profile 冻结的 runtime_kind 精确解析；未部署 = 显式拒绝 */
    public RoleRunner requireFor(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        RoleRunner runner = byKind.get(profile.runtimeKind());
        if (runner == null) {
            throw new CapabilityUnavailableException(
                    "CAPABILITY_UNAVAILABLE: 角色 " + profile.name() + "@"
                            + profile.version() + " 声明 runtime_kind="
                            + profile.runtimeKind() + " 无已部署运行器");
        }
        return runner;
    }

    /** 目录可见性（装配自检面） */
    public List<String> deployedKinds() {
        return List.copyOf(byKind.keySet());
    }

    /** 运行器不可用（RX03 准入/执行面同码）：绑定角色声明的运行器类型未部署 */
    public static final class CapabilityUnavailableException extends RuntimeException {

        public CapabilityUnavailableException(String message) {
            super(message);
        }
    }
}

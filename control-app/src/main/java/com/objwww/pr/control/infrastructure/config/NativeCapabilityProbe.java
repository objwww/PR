package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.shared.Digest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * NATIVE 执行面能力探针（M6-01 落点 10；C-67）：就绪判定与 capability digest
 * 均为装配事实的<b>纯函数</b>——缺任一依赖（bean 缺件 / 配置空）即 not ready，
 * 路由面据此把 NATIVE 意愿降级 HOLMES（NATIVE_DEFERRED）。<b>无 nativeReady 热
 * 开关</b>：装配变化只能经重启生效，消除双事实源与伪原子切换。
 *
 * <p>capability digest = 实际在场组件（名字+运行时类）与外部依赖配置的 canonical
 * 散列——V30 窗判定的 capability_digest 同源；not ready 时拒绝出具 digest
 * （不完整能力没有指纹可言，fail-closed）。
 *
 * @author wanghua
 * @date 2026-09-08
 */
public final class NativeCapabilityProbe {

    private final List<String> missing;
    private final Digest capabilityDigest;

    /**
     * @param components         装配组件（名字 → 实例；null = 缺件）
     * @param metricsExpr        症状指标数据源表达式（空 = 缺件）
     * @param toolRegistryDigest 工具注册面 digest（空 = 缺件——快照身份面）
     */
    public NativeCapabilityProbe(Map<String, ?> components,
                                 String metricsExpr, String toolRegistryDigest) {
        Objects.requireNonNull(components, "components 不得为 null");
        Map<String, String> present = new LinkedHashMap<>();
        List<String> missing = new java.util.ArrayList<>();
        for (Map.Entry<String, ?> e : components.entrySet()) {
            if (e.getValue() == null) {
                missing.add(e.getKey());
            } else {
                present.put(e.getKey(), e.getValue().getClass().getName());
            }
        }
        if (metricsExpr == null || metricsExpr.isBlank()) {
            missing.add("metricsExpr");
        } else {
            present.put("metricsExpr", metricsExpr.trim());
        }
        if (toolRegistryDigest == null || toolRegistryDigest.isBlank()) {
            missing.add("toolRegistryDigest");
        } else {
            present.put("toolRegistryDigest", toolRegistryDigest.trim());
        }
        this.missing = List.copyOf(missing);
        if (this.missing.isEmpty()) {
            StringBuilder canonical = new StringBuilder();
            new java.util.TreeMap<>(present).forEach((name, value) ->
                    canonical.append(name).append('=').append(value).append('\n'));
            this.capabilityDigest = Digest.sha256Of(canonical.toString());
        } else {
            this.capabilityDigest = null;
        }
    }

    /** NATIVE 执行面是否就绪（缺任一依赖即 false → 路由面 NATIVE_DEFERRED） */
    public boolean ready() {
        return missing.isEmpty();
    }

    /** 缺件清单（bean 名/配置键；ready 时空表） */
    public List<String> missing() {
        return missing;
    }

    /** capability digest（not ready 抛 IllegalStateException——无指纹可言） */
    public Digest capabilityDigest() {
        if (capabilityDigest == null) {
            throw new IllegalStateException("capability 不完整，无 digest: " + missing);
        }
        return capabilityDigest;
    }
}

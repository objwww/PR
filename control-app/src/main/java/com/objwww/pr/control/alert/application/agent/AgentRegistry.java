package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.AgentProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 注册表（AM4 M4-24，对齐 ToolRegistry 惯例）：<b>不可自由生 Agent</b> 的
 * 结构卡点——启动期一次性构造 fail-fast（空注册表/null 项/重名同版本含 digest 冲突
 * 全部拒绝），构造后不可变、无运行时注册 API；消费面只有 {@link #require}（未注册
 * Agent / 未声明版本显式拒绝）、{@link #find}、{@link #all}（name+version 字典序稳定）。
 */
public final class AgentRegistry {

    private final Map<String, AgentProfile> profiles;
    private final List<AgentProfile> sorted;

    public AgentRegistry(List<AgentProfile> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        if (registrations.isEmpty()) {
            throw new IllegalStateException("Agent 注册表不得为空——无 Agent 可执行");
        }
        for (AgentProfile profile : registrations) {
            if (profile == null) {
                throw new IllegalStateException("Agent 注册表含 null 项");
            }
        }
        this.sorted = registrations.stream()
                .sorted(Comparator.comparing(AgentProfile::name)
                        .thenComparing(AgentProfile::version))
                .toList();
        Map<String, AgentProfile> byKey = registrations.stream().collect(Collectors.toMap(
                p -> key(p.name(), p.version()), Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Agent 重名同版本冲突: "
                            + a.name() + "@" + a.version()
                            + "（digest " + a.digest() + " vs " + b.digest() + "）");
                }));
        this.profiles = Map.copyOf(byKey);
    }

    /** 未注册 Agent / 未声明版本显式拒绝（不可自由生 Agent 的执行点） */
    public AgentProfile require(String name, String version) {
        return Optional.ofNullable(profiles.get(key(name, version)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent 未注册: " + name + "@" + version));
    }

    public Optional<AgentProfile> find(String name, String version) {
        return Optional.ofNullable(profiles.get(key(name, version)));
    }

    /** 全量快照（name+version 字典序稳定；不可变） */
    public List<AgentProfile> all() {
        return sorted;
    }

    private static String key(String name, String version) {
        return name + "@" + Objects.requireNonNull(version, "version");
    }
}

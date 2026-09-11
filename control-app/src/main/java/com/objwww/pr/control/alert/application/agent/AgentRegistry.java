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
 *
 * <p>R7-X3（v2.1 §十一.5）：角色目录随 release 固定——{@link #forRelease} 按 release
 * 构造启动期不可变快照，新 release 构造新快照，不直接修改共享 Map；进行中 Run 不因
 * 注册表变化漂移（恢复按持久绑定的 (name, version, digest) 经 {@link #requireExact}
 * 精确解析，同名同版本 digest 漂移 = 冒名顶替显式拒绝）。
 */
public final class AgentRegistry {

    private final Map<String, AgentProfile> profiles;
    private final List<AgentProfile> sorted;
    /** 本快照所属 release 的 digest（null = 无 release 锚的旧装配形态） */
    private final String releaseDigest;

    /** 按 release 构造启动期不可变快照（§十一.5：角色目录随 release 固定） */
    public static AgentRegistry forRelease(String releaseDigest, List<AgentProfile> registrations) {
        if (releaseDigest == null || releaseDigest.isBlank()) {
            throw new IllegalArgumentException("releaseDigest 不得为空/blank");
        }
        return new AgentRegistry(registrations, releaseDigest);
    }

    public AgentRegistry(List<AgentProfile> registrations) {
        this(registrations, null);
    }

    private AgentRegistry(List<AgentProfile> registrations, String releaseDigest) {
        Objects.requireNonNull(registrations, "registrations");
        this.releaseDigest = releaseDigest;
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

    /**
     * R7-X4 委派裁决解析：按 role_id（名）钉唯一版本——快照内同名多版本 = 歧义显式
     * 拒绝（不猜 latest，与 §十一.2 恢复纪律同律）；未注册同拒。
     */
    public AgentProfile requireByName(String name) {
        Objects.requireNonNull(name, "name");
        List<AgentProfile> matches = sorted.stream()
                .filter(p -> p.name().equals(name))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("角色未注册: " + name);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("角色版本歧义（不猜 latest）: " + name
                    + " 有 " + matches.size() + " 个注册版本");
        }
        return matches.get(0);
    }

    /**
     * §十一.2 恢复纪律：按持久绑定的 (name, version, digest) 精确解析——
     * 同名同版本但 digest 漂移 = 冒名顶替，显式拒绝（不选 latest、不串用同名各版本）。
     */
    public AgentProfile requireExact(String name, String version, String expectedDigest) {
        AgentProfile profile = require(name, version);
        if (!profile.digest().equals(expectedDigest)) {
            throw new IllegalArgumentException("Agent digest 漂移: " + name + "@" + version
                    + "（绑定=" + expectedDigest + " 注册=" + profile.digest() + "）");
        }
        return profile;
    }

    /** 本快照的 release 锚（无锚旧形态返回 empty） */
    public Optional<String> releaseDigest() {
        return Optional.ofNullable(releaseDigest);
    }

    /** 全量快照（name+version 字典序稳定；不可变） */
    public List<AgentProfile> all() {
        return sorted;
    }

    private static String key(String name, String version) {
        return name + "@" + Objects.requireNonNull(version, "version");
    }
}

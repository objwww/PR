package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.tool.ToolDefinition;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本地工具注册表（AM4 M4-14）：<b>仅启动期</b>由装配面一次性构造——重名/版本冲突
 * fail-fast（同名同版本无论 schema 异同都拒绝，禁静默覆盖）；构造后不可变（无运行时
 * 注册 API），"禁运行时网络下载插件"由结构保证 + ControlArchitectureTest 断言
 * （tool 包禁触网络 API）。
 */
public final class ToolRegistry {

    /** 注册项：定义 + 执行器（执行器与 Schema 的方法签名一致性校验随首个真实工具落地，M4-27） */
    public record Registration(ToolDefinition definition, ToolExecutor executor) {
    }

    private record Key(String name, String version) {
    }

    private final Map<Key, Registration> registrations = new LinkedHashMap<>();

    public ToolRegistry(List<Registration> registrations) {
        for (Registration r : registrations) {
            if (r == null || r.definition() == null || r.executor() == null) {
                throw new IllegalArgumentException("注册项及 definition/executor 均不得为 null");
            }
            Key key = new Key(r.definition().name(), r.definition().version());
            Registration previous = this.registrations.putIfAbsent(key, r);
            if (previous != null) {
                throw new IllegalStateException("工具重名/版本冲突（启动期 fail-fast，禁静默覆盖）: "
                        + r.definition().name() + "@" + r.definition().version()
                        + " schema_hash=" + r.definition().schemaHash() + " 与 "
                        + previous.definition().schemaHash() + " 冲突");
            }
        }
        if (this.registrations.isEmpty()) {
            throw new IllegalStateException("空工具注册表（启动期硬失败，M4-14）："
                    + "至少注册一个工具，否则应用拒绝启动");
        }
    }

    public Optional<Registration> find(String name, String version) {
        return Optional.ofNullable(registrations.get(new Key(name, version)));
    }

    /** 全量注册项（name,version 字典序稳定——LLM 下发清单顺序确定） */
    public List<Registration> all() {
        return registrations.values().stream()
                .sorted(Comparator.comparing(r -> r.definition().name() + "@"
                        + r.definition().version()))
                .toList();
    }
}

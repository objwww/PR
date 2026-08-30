package com.objwww.pr.control.domain.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具登记表（M0 最小）：仅两个只读工具。
 * 注意：Registry 发现 ≠ 授权（§3）——能不能调由 PolicyEngine 裁决，本类只回答"存在什么"。
 * 自由探索/写工具是 M2+ 的事，M0 不开放注册入口（无 register 方法）。
 */
public final class ToolRegistry {

    public static final String READ_FILE = "read_file";
    public static final String SEARCH_IN_SNAPSHOT = "search_in_snapshot";

    private final Map<String, ToolDescriptor> tools;

    public ToolRegistry() {
        this(Map.of(
                READ_FILE, new ToolDescriptor(READ_FILE, "读取快照中单个文件的内容", true),
                SEARCH_IN_SNAPSHOT,
                        new ToolDescriptor(SEARCH_IN_SNAPSHOT, "在快照内按关键字搜索文件/内容", true)));
    }

    /** 带参构造为测试与 M2+ 工具扩展预留；授权裁决始终由 PolicyEngine 负责 */
    public ToolRegistry(Map<String, ToolDescriptor> tools) {
        Map<String, ToolDescriptor> copy = new LinkedHashMap<>(Objects.requireNonNull(tools));
        this.tools = Map.copyOf(copy);
    }

    public Optional<ToolDescriptor> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDescriptor> list() {
        return List.copyOf(tools.values());
    }
}

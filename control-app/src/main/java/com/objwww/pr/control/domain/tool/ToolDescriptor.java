package com.objwww.pr.control.domain.tool;

import java.util.Objects;

/**
 * 工具描述符（M0 最小）：名字 + 说明 + 只读标记。
 * 描述存在 ≠ 可调参数 schema——M0 不做 JSON Schema，M2 工具循环放开时再补。
 */
public record ToolDescriptor(String name, String description, boolean readOnly) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        Objects.requireNonNull(description, "description");
    }
}

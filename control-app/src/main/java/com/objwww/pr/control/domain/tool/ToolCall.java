package com.objwww.pr.control.domain.tool;

import java.util.Map;

/** 一次工具调用请求（模型产出、执行前必过 PolicyEngine.check） */
public record ToolCall(String toolName, Map<String, String> arguments) {

    public ToolCall {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写类审批工具目录（BA-171）：service.restart / service.rollback 两个 R3 高危工具。
 *
 * <p>语义铁律：R3 在当前策略下<b>永不真实执行</b>——ToolGateway 对非 executable
 * 风险级走 VALIDATE_ONLY 短路（铸 action_intent 意图 + 自动进审批队列），执行器
 * 永远不被触达；占位执行器被调用即装配缺陷（构造期 ToolRegistry 拒 null 执行器，
 * 故显式占位并显式 ToolRisk.R3——缺省虽从严 R3，显式更可读）。
 *
 * <p>资源键约定：args["service"] 原样作为请求面资源键——授权身份只信
 * resource_alias/resource_inventory 的权威解析（PostgresResourceResolver
 * fail-closed），键格式以 Resolver 为准。
 */
public final class MutationToolCatalog {

    public static final String TOOL_SERVICE_RESTART = "service.restart";
    public static final String TOOL_SERVICE_ROLLBACK = "service.rollback";
    public static final String VERSION = "1";

    private MutationToolCatalog() {
    }

    /** service.restart：重启服务容器（R3，需两人审批） */
    public static ToolDefinition serviceRestart(long timeoutMillis, long resultLimitBytes) {
        return definition(TOOL_SERVICE_RESTART, timeoutMillis, resultLimitBytes);
    }

    /** service.rollback：回滚服务到上一版本（R3，需两人审批） */
    public static ToolDefinition serviceRollback(long timeoutMillis, long resultLimitBytes) {
        return definition(TOOL_SERVICE_ROLLBACK, timeoutMillis, resultLimitBytes);
    }

    /**
     * 占位执行器（永不触网）：R3 工具的真实执行面不存在——VALIDATE_ONLY 之外触达
     * 本执行器 = 装配缺陷，显式炸出而不是静默假装执行。
     */
    public static ToolExecutor nonExecutablePlaceholder(String toolName) {
        return execution -> {
            throw new IllegalStateException(toolName + " 为 R3 审批工具：执行器永不触网，"
                    + "VALIDATE_ONLY 短路之外触达 = 装配缺陷");
        };
    }

    private static ToolDefinition definition(String name, long timeoutMillis,
            long resultLimitBytes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("service", Map.of("type", "string", "maxLength", 128));
        properties.put("reason", Map.of("type", "string", "maxLength", 512));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("service"));
        return new ToolDefinition(name, VERSION, schema, ToolRisk.R3,
                timeoutMillis, resultLimitBytes);
    }
}

package com.objwww.pr.control.domain.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 工具策略：M0 仅两个只读工具；每次调用必过 PolicyEngine；非白名单拒绝。
 */
class PolicyEngineTest {

    private final PolicyEngine policy = new PolicyEngine(new ToolRegistry());

    @Test
    void registryContainsExactlyTwoReadOnlyTools() {
        ToolRegistry registry = new ToolRegistry();
        assertEquals(2, registry.list().size());
        assertTrue(registry.find(ToolRegistry.READ_FILE).orElseThrow().readOnly());
        assertTrue(registry.find(ToolRegistry.SEARCH_IN_SNAPSHOT).orElseThrow().readOnly());
    }

    @Test
    void whitelistedReadOnlyToolsAreAllowed() {
        assertTrue(policy.check(new ToolCall(ToolRegistry.READ_FILE, Map.of("path", "src/Main.java"))).allowed());
        assertTrue(policy.check(new ToolCall(ToolRegistry.SEARCH_IN_SNAPSHOT, Map.of("keyword", "password"))).allowed());
    }

    @Test
    void nonWhitelistedToolIsRejected() {
        PolicyVerdict verdict = policy.check(new ToolCall("execute_shell", Map.of("cmd", "rm -rf /")));
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("非白名单"));
    }

    @Test
    void writeToolIsRejectedEvenIfRegistered() {
        // Registry 发现 ≠ 授权：即使未来登记表里出现写工具，Policy 仍按只读规则拦（M0）
        ToolRegistry withWriteTool = new ToolRegistry(Map.of(
                ToolRegistry.READ_FILE, new ToolDescriptor(ToolRegistry.READ_FILE, "读文件", true),
                "write_file", new ToolDescriptor("write_file", "写文件", false)));
        PolicyEngine strict = new PolicyEngine(withWriteTool);
        PolicyVerdict verdict = strict.check(new ToolCall("write_file", Map.of()));
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("非只读"));
    }
}

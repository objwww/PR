package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.mcp.McpMountManager;
import com.objwww.pr.control.alert.application.mcp.McpMountManager.MountStatus;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 挂载管理面 REST（EN-06，§2.3 线 91：管理面=控制面 REST）。类不带 stereotype
 * 注解、由 McpMountConfig 按 {@code app.alert.mcp.mount-enabled=true} 条件注册 bean
 * （fail-closed 默认关；@RequestMapping 仅供 MVC handler 探测）。路径
 * /api/mcp-servers/** 归 OPERATOR 角色（SecurityConfig 矩阵）。M11 拒绝在管理面即
 * 映射 403（发布前拒绝，零连接零落库）。
 */
@RequestMapping("/api/mcp-servers")
public class McpMountController {

    public record RegisterRequest(String name, String transport, String endpoint,
            List<String> args, String headersRef) {
    }

    private final McpMountManager manager;

    public McpMountController(McpMountManager manager) {
        this.manager = manager;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        try {
            MountStatus status = manager.register(request.name(), request.transport(),
                    request.endpoint(), request.args(), request.headersRef());
            if (status.state() == McpMountManager.ServerState.FAILED) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(face(status.serverName(), status.state().name(), status.reason(),
                                status.generation()));
            }
            return ResponseEntity.ok(face(status.serverName(), status.state().name(), null,
                    status.generation()));
        } catch (ToolControlPlaneException e) {
            return ResponseEntity.status(
                            e.reason() == ToolControlReason.POLICY_DENIED
                                    ? HttpStatus.FORBIDDEN : HttpStatus.CONFLICT)
                    .body(Map.of("reason", e.reason().name(),
                            "message", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/{name}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable String name) {
        return respond(name, () -> manager.disable(name));
    }

    @PostMapping("/{name}/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable String name) {
        return respond(name, () -> manager.enable(name));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deregister(@PathVariable String name,
            @RequestParam(defaultValue = "PT30S") Duration drainTimeout) {
        manager.deregister(name, drainTimeout);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Map<String, MountStatus> status() {
        Map<String, MountStatus> statuses = new LinkedHashMap<>();
        manager.snapshot().servers()
                .forEach((name, entry) -> manager.status(name).ifPresent(s -> statuses.put(name, s)));
        return statuses;
    }

    private ResponseEntity<Map<String, Object>> respond(String name,
            java.util.function.Supplier<MountStatus> action) {
        try {
            MountStatus status = action.get();
            return ResponseEntity.ok(face(status.serverName(), status.state().name(),
                    status.reason(), status.generation()));
        } catch (ToolControlPlaneException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("reason", e.reason().name(),
                            "message", String.valueOf(e.getMessage())));
        }
    }

    private static Map<String, Object> face(String name, String state, String reason,
            long generation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("state", state);
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("generation", generation);
        return body;
    }
}

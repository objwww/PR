package com.objwww.pr.control.alert.domain.dag;

import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Planner 结构化提案（AM4 M4-25，双设防第一设防）：LLM 输出不可信，解析即严格校验——
 * schema 版本钉死、未知字段显式拒绝（禁静默裁字段，GX-4 同纪律）、task key 重复/
 * edge 端点未声明/自环/同对重复边（同型重复与异型冲突都拒）在结构面全部拦下。
 * 语义面（注册表类型/数量/深度/无环复判/artifact 引用）在 PlanCompiler。
 *
 * <p>type 统一 {@code name@version} 全钉格式——相同提案 → 相同任务图（可复现，
 * 不存在"注册表最新版"漂移）。digest 与解析输入的字段/列表顺序无关。
 */
public record PlanProposal(
        String schemaVersion,
        List<PlanTask> tasks,
        List<PlanEdge> edges) {

    public static final String SCHEMA_VERSION = "am4-plan.v1";

    public PlanProposal {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** 提案任务（inputs = 引用本 run 已知 artifact 的键，缺省空） */
    public record PlanTask(String key, String type, List<String> inputs) {
        public PlanTask {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("task.key 不得为空");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("task.type 不得为空");
            }
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        }
    }

    /** 提案边（端点为 task key；dependency 为 REQUIRED/OPTIONAL） */
    public record PlanEdge(String from, String to, DependencyType dependency) {
        public PlanEdge {
            if (from == null || from.isBlank() || to == null || to.isBlank()) {
                throw new IllegalArgumentException("edge 端点不得为空");
            }
            Objects.requireNonNull(dependency, "dependency");
        }
    }

    /** 严格解析：任何形状偏差/未知字段/结构冲突都抛 IllegalArgumentException */
    public static PlanProposal parse(Map<String, Object> raw) {
        Objects.requireNonNull(raw, "planner 输出必须是 JSON 对象");
        requireAllowedKeys(raw, Set.of("schema_version", "tasks", "edges"), "顶层");
        if (!SCHEMA_VERSION.equals(raw.get("schema_version"))) {
            throw new IllegalArgumentException(
                    "schema_version 必须为 " + SCHEMA_VERSION + "，实际: " + raw.get("schema_version"));
        }
        if (!(raw.get("tasks") instanceof List<?> rawTasks) || rawTasks.isEmpty()) {
            throw new IllegalArgumentException("tasks 必须是非空数组");
        }
        List<PlanTask> tasks = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : rawTasks) {
            if (!(item instanceof Map<?, ?> rawTask)) {
                throw new IllegalArgumentException("task 必须是对象: " + item);
            }
            Map<String, Object> task = asStringMap(rawTask);
            requireAllowedKeys(task, Set.of("key", "type", "inputs"), "task");
            String key = requireString(task, "key");
            String type = requireString(task, "type");
            requireTypePinned(type);
            List<String> inputs = inputsOf(task);
            if (!keys.add(key)) {
                throw new IllegalArgumentException("task key 重复: " + key);
            }
            tasks.add(new PlanTask(key, type, inputs));
        }

        List<PlanEdge> edges = new ArrayList<>();
        Object rawEdges = raw.get("edges");
        if (rawEdges != null) {
            if (!(rawEdges instanceof List<?> edgeList)) {
                throw new IllegalArgumentException("edges 必须是数组");
            }
            for (Object item : edgeList) {
                if (!(item instanceof Map<?, ?> rawEdge)) {
                    throw new IllegalArgumentException("edge 必须是对象: " + item);
                }
                Map<String, Object> edge = asStringMap(rawEdge);
                requireAllowedKeys(edge, Set.of("from", "to", "dependency"), "edge");
                String from = requireString(edge, "from");
                String to = requireString(edge, "to");
                if (from.equals(to)) {
                    throw new IllegalArgumentException("edge 自环: " + from);
                }
                if (!keys.contains(from) || !keys.contains(to)) {
                    throw new IllegalArgumentException(
                            "edge 端点引用未声明的 task key: " + from + " -> " + to);
                }
                DependencyType dependency = parseDependency(edge.get("dependency"));
                PlanEdge candidate = new PlanEdge(from, to, dependency);
                for (PlanEdge existing : edges) {
                    if (existing.from().equals(from) && existing.to().equals(to)) {
                        if (existing.dependency() == dependency) {
                            throw new IllegalArgumentException(
                                    "edge 重复: " + from + " -> " + to);
                        }
                        throw new IllegalArgumentException(
                                "edge 冲突（同对异型）: " + from + " -> " + to);
                    }
                }
                edges.add(candidate);
            }
        }
        return new PlanProposal(SCHEMA_VERSION, tasks, edges);
    }

    /** 规范化摘要：相同提案必同 digest（任务按 key 序、边按端点序、输入排序） */
    public String digest() {
        List<Map<String, Object>> taskMaps = tasks.stream()
                .sorted(Comparator.comparing(PlanTask::key))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", t.key());
                    m.put("type", t.type());
                    m.put("inputs", t.inputs().stream().sorted().toList());
                    return m;
                })
                .toList();
        List<Map<String, Object>> edgeMaps = edges.stream()
                .sorted(Comparator.comparing(PlanEdge::from).thenComparing(PlanEdge::to))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", e.from());
                    m.put("to", e.to());
                    m.put("dependency", e.dependency().name());
                    return m;
                })
                .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "plan-proposal");
        content.put("schema_version", schemaVersion);
        content.put("tasks", taskMaps);
        content.put("edges", edgeMaps);
        return InternalCanonicalJsonV1.sha256(content);
    }

    // ------------------------------------------------------------------ 内部

    private static void requireAllowedKeys(Map<String, Object> map, Set<String> allowed,
            String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(where + "存在未声明字段（禁静默裁字段）: " + key);
            }
        }
    }

    private static String requireString(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return s;
    }

    /** type 必须 name@version 全钉（防注册表版本漂移破坏可复现性） */
    private static void requireTypePinned(String type) {
        int at = type.indexOf('@');
        if (at <= 0 || at == type.length() - 1
                || type.indexOf('@', at + 1) >= 0) {
            throw new IllegalArgumentException(
                    "task.type 必须为 name@version 全钉格式: " + type);
        }
    }

    private static List<String> inputsOf(Map<String, Object> task) {
        Object raw = task.get("inputs");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("task.inputs 必须是数组");
        }
        return list.stream().map(String::valueOf).map(String::strip).toList();
    }

    private static DependencyType parseDependency(Object raw) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException("edge.dependency 必须是 REQUIRED|OPTIONAL");
        }
        try {
            return DependencyType.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("edge.dependency 必须是 REQUIRED|OPTIONAL: " + s);
        }
    }

    private static Map<String, Object> asStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new IllegalArgumentException("字段名必须是字符串: " + e.getKey());
            }
            out.put(key, e.getValue());
        }
        return out;
    }
}

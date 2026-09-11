package com.objwww.pr.control.alert.domain.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 主 Agent 互斥 Decision（R7-X11，v2.1 §三）：模型输出经本类严格解析后执行——
 * 每次只允许一个分支：TOOL_CALL（直接取证）/ DELEGATE（一批有界委派）/ FINAL
 * （唯一模型 Claim 提案出口）。解析即校验：未知字段显式拒绝（PlanProposal 同纪律，
 * 禁静默裁字段）、互斥由"恰好一个分支键"结构性保证；DELEGATE 的请求批形状
 * （gap_id/role_id/question/input_refs/scope/requested_budget 必填）在本面钉死。
 *
 * <p>模型输出不可信：本类只做结构裁决；目录/权限/预算/去重/任务上限等确定性校验
 * 归 Supervisor（v2.1 §三 "确定性 Supervisor 校验目录、权限、预算、去重和任务上限"）。
 */
public record PrimaryDecision(Branch branch, ToolCall toolCall, List<DelegateRequest> delegate,
        FinalAnswer finalAnswer) {

    public enum Branch {TOOL_CALL, DELEGATE, FINAL}

    /** TOOL_CALL{tool_id,args}：一次受限直接取证 */
    public record ToolCall(String toolId, Map<String, Object> args) {
        public ToolCall {
            if (toolId == null || toolId.isBlank()) {
                throw new IllegalArgumentException("tool_id 必须是非空字符串");
            }
            args = Objects.requireNonNull(args, "args");
        }
    }

    /** DELEGATE 请求项：{gap_id,role_id,question,input_refs,scope,requested_budget} */
    public record DelegateRequest(String gapId, String roleId, String question,
            List<String> inputRefs, Map<String, Object> scope, Long requestedBudget) {
        public DelegateRequest {
            if (gapId == null || gapId.isBlank()) {
                throw new IllegalArgumentException("gap_id 必须是非空字符串");
            }
            if (roleId == null || roleId.isBlank()) {
                throw new IllegalArgumentException("role_id 必须是非空字符串");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question 必须是非空字符串");
            }
            inputRefs = List.copyOf(Objects.requireNonNull(inputRefs, "inputRefs"));
            scope = Objects.requireNonNull(scope, "scope");
        }
    }

    /**
     * FINAL{claims,missing_information}：主 Agent 的 Claim 提案出口。
     * evidenceRefs 为该 claim 依据的本 run 证据引用（准入面按此核对）。
     */
    public record FinalClaim(String claimKey, String kind, String statement,
            List<String> evidenceRefs) {
        public FinalClaim {
            if (claimKey == null || claimKey.isBlank()) {
                throw new IllegalArgumentException("claim.claim_key 必须是非空字符串");
            }
            if (statement == null || statement.isBlank()) {
                throw new IllegalArgumentException("claim.statement 必须是非空字符串");
            }
            evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
        }
    }

    public record FinalAnswer(List<FinalClaim> claims, List<String> missingInformation) {
        public FinalAnswer {
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            missingInformation = List.copyOf(Objects.requireNonNull(missingInformation,
                    "missingInformation"));
        }
    }

    public PrimaryDecision {
        Objects.requireNonNull(branch, "branch");
        delegate = List.copyOf(Objects.requireNonNull(delegate, "delegate"));
        switch (branch) {
            case TOOL_CALL -> Objects.requireNonNull(toolCall, "TOOL_CALL 必须携带 toolCall");
            case DELEGATE -> {
                if (delegate.isEmpty()) {
                    throw new IllegalArgumentException("DELEGATE 至少一个请求");
                }
            }
            case FINAL -> Objects.requireNonNull(finalAnswer, "FINAL 必须携带 finalAnswer");
        }
    }

    /** 严格解析：恰好一个分支键；未声明字段拒绝；分支载荷形状面校验 */
    public static PrimaryDecision parse(Map<String, Object> raw) {
        Objects.requireNonNull(raw, "模型决策输出必须是 JSON 对象");
        requireAllowedKeys(raw, Set.of("tool_call", "delegate", "final"), "decision 顶层");
        List<String> present = new java.util.ArrayList<>();
        if (raw.containsKey("tool_call")) {
            present.add("tool_call");
        }
        if (raw.containsKey("delegate")) {
            present.add("delegate");
        }
        if (raw.containsKey("final")) {
            present.add("final");
        }
        if (present.size() != 1) {
            throw new IllegalArgumentException(
                    "decision 互斥违规：必须恰好一个分支，实际 " + present);
        }
        if (raw.containsKey("tool_call")) {
            if (!(raw.get("tool_call") instanceof Map<?, ?> rawTool)) {
                throw new IllegalArgumentException("tool_call 必须是对象");
            }
            Map<String, Object> tool = asStringMap(rawTool);
            requireAllowedKeys(tool, Set.of("tool_id", "args"), "tool_call");
            Map<String, Object> args = tool.containsKey("args")
                    ? asStringMap(requireMap(tool.get("args"), "args"))
                    : Map.of();
            return new PrimaryDecision(Branch.TOOL_CALL,
                    new ToolCall(requireString(tool, "tool_id"), args), List.of(), null);
        }
        if (raw.containsKey("delegate")) {
            if (!(raw.get("delegate") instanceof Map<?, ?> rawDel)) {
                throw new IllegalArgumentException("delegate 必须是对象{requests:[...]}");
            }
            Map<String, Object> del = asStringMap(rawDel);
            requireAllowedKeys(del, Set.of("requests"), "delegate");
            if (!(del.get("requests") instanceof List<?> rawRequests)
                    || rawRequests.isEmpty()) {
                throw new IllegalArgumentException(
                        "delegate.requests 必须是非空数组（至少一个请求）");
            }
            List<DelegateRequest> requests = new java.util.ArrayList<>();
            for (Object item : rawRequests) {
                Map<String, Object> req = asStringMap(requireMap(item, "request"));
                requireAllowedKeys(req, Set.of("gap_id", "role_id", "question",
                        "input_refs", "scope", "requested_budget"), "delegate request");
                requests.add(new DelegateRequest(
                        requireString(req, "gap_id"),
                        requireString(req, "role_id"),
                        requireString(req, "question"),
                        stringsOf(req.get("input_refs"), "input_refs"),
                        req.containsKey("scope")
                                ? asStringMap(requireMap(req.get("scope"), "scope"))
                                : Map.of(),
                        req.get("requested_budget") instanceof Number n
                                ? n.longValue() : null));
            }
            return new PrimaryDecision(Branch.DELEGATE, null, requests, null);
        }
        Map<String, Object> fin = asStringMap(requireMap(raw.get("final"), "final"));
        requireAllowedKeys(fin, Set.of("claims", "missing_information"), "final");
        List<FinalClaim> claims = new java.util.ArrayList<>();
        if (fin.get("claims") != null) {
            if (!(fin.get("claims") instanceof List<?> rawClaims)) {
                throw new IllegalArgumentException("final.claims 必须是数组");
            }
            for (Object item : rawClaims) {
                Map<String, Object> c = asStringMap(requireMap(item, "claim"));
                requireAllowedKeys(c, Set.of("claim_key", "kind", "statement",
                        "evidence_refs"), "claim");
                claims.add(new FinalClaim(requireString(c, "claim_key"),
                        c.get("kind") == null ? null : requireString(c, "kind"),
                        requireString(c, "statement"),
                        stringsOf(c.get("evidence_refs"), "evidence_refs")));
            }
        }
        List<String> missing = fin.get("missing_information") == null
                ? List.of()
                : stringsOf(fin.get("missing_information"), "missing_information");
        return new PrimaryDecision(Branch.FINAL, null, List.of(),
                new FinalAnswer(claims, missing));
    }

    // ------------------------------------------------------------------ 内部

    private static void requireAllowedKeys(Map<String, Object> map, Set<String> allowed,
            String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        where + "存在未声明字段（禁静默裁字段）: " + key);
            }
        }
    }

    private static String requireString(Map<String, Object> map, String field) {
        if (!(map.get(field) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(field + " 必须是非空字符串");
        }
        return s;
    }

    private static Map<?, ?> requireMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        return m;
    }

    private static List<String> stringsOf(Object raw, String field) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " 必须是数组");
        }
        return list.stream().map(String::valueOf).map(String::strip).toList();
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

    /** 分支名（小写，日志/事件面稳定标识） */
    public String branchName() {
        return branch.name().toLowerCase(Locale.ROOT);
    }
}

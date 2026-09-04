package com.objwww.pr.control.alert.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构验证链（§6.5；AM1 边界 = 结构验证，语义验证归 AM4）。
 *
 * <p>链路：响应尺寸 → 解析 Holmes 外层响应（官方 ChatResponse.analysis 字段，http-api.md）→
 * 解析 analysis 内嵌 JSON 字符串 →
 * schema_version 校验 → 六段式字段存在/类型 → 字段数量/长度限制 → redaction 脱敏 →
 * STRUCTURE_VALIDATED。
 *
 * <p>六段式 schema v1：{schema_version, summary, root_cause, evidence[], impact, remediation,
 * references[{artifact_ref}]}。references 只允许安全 artifact_ref（prometheus:// / dashboard://），
 * 禁止凭证与任意外链（评审修正；AFT-A03 守字段扫描）。
 */
public final class EvidencePackageValidator {

    /** 六段式必备字段（references 可为空数组但键必须在） */
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("schema_version", "summary", "root_cause", "evidence", "impact",
                    "remediation", "references");

    private static final Set<String> TEXT_FIELDS =
            Set.of("summary", "root_cause", "impact", "remediation");

    /** artifact_ref 只许的安全 scheme */
    private static final Pattern SAFE_REF = Pattern.compile("^(prometheus|dashboard)://[^\\s]+$");

    /** markdown 代码围栏（BA-14：DashScope 忽略 response_format 时模型的常见包裹形态） */
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);

    /** 脱敏命中面：sk- 密钥 / Bearer token / ≥32 位长 hex（INV-AM1-8 raw 入库前脱敏） */
    private static final Pattern[] REDACTIONS = {
            Pattern.compile("sk-[A-Za-z0-9_-]{8,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._-]{8,}"),
            Pattern.compile("[0-9a-fA-F]{32,}")
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxResponseBytes;
    private final int expectedSchemaVersion;
    private final int maxEvidenceItems;
    private final int maxFieldChars;

    public EvidencePackageValidator(int maxResponseBytes, int expectedSchemaVersion,
                                    int maxEvidenceItems, int maxFieldChars) {
        if (maxResponseBytes <= 0 || expectedSchemaVersion < 1
                || maxEvidenceItems < 1 || maxFieldChars < 1) {
            throw new IllegalArgumentException("验证参数必须为正");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.expectedSchemaVersion = expectedSchemaVersion;
        this.maxEvidenceItems = maxEvidenceItems;
        this.maxFieldChars = maxFieldChars;
    }

    /** 验证结果：状态 + 拒绝原因链 + 规范化 package + 脱敏后的原文 */
    public record Result(ValidationStatus status, List<String> errors,
                         String packageJson, String redactedRawText) {
    }

    public Result validate(String holmesResponseBody) {
        List<String> errors = new ArrayList<>();
        try {
            return doValidate(holmesResponseBody, errors);
        } catch (Malformed e) {
            errors.add(e.getMessage());
            return new Result(ValidationStatus.REJECTED_MALFORMED, List.copyOf(errors), null, redact(holmesResponseBody));
        }
    }

    private Result doValidate(String body, List<String> errors) throws Malformed {
        // 1. 响应尺寸
        if (body == null || body.isEmpty()) {
            throw new Malformed("Holmes 响应为空");
        }
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxResponseBytes) {
            errors.add("响应超过 " + maxResponseBytes + " 字节上限");
            return new Result(ValidationStatus.REJECTED_OVERSIZE, List.copyOf(errors), null, redact(body));
        }

        // 2. Holmes 外层响应（官方 ChatResponse）：对象 + analysis(字符串)
        JsonNode outer = parse(body);
        if (!outer.isObject() || !outer.has("analysis") || !outer.get("analysis").isTextual()) {
            throw new Malformed("Holmes 外层响应缺 analysis 字符串字段");
        }

        // 3. analysis 内嵌 JSON 字符串
        JsonNode pkg = parseAnalysis(outer.get("analysis").asText());

        // 4. schema_version
        if (!pkg.has("schema_version") || !pkg.get("schema_version").isInt()) {
            throw new Malformed("package 缺整型 schema_version");
        }
        int version = pkg.get("schema_version").asInt();
        if (version != expectedSchemaVersion) {
            errors.add("schema_version=" + version + " 不匹配期望 " + expectedSchemaVersion);
            return new Result(ValidationStatus.REJECTED_SCHEMA_VERSION, List.copyOf(errors), null, redact(body));
        }

        // 5. 六段式字段存在 + 类型
        if (!pkg.isObject()) {
            throw new Malformed("analysis 不是 JSON 对象");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!pkg.has(field)) {
                errors.add("缺少字段: " + field);
            }
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        for (String field : TEXT_FIELDS) {
            if (!pkg.get(field).isTextual() || pkg.get(field).asText().isBlank()) {
                errors.add("字段 " + field + " 必须为非空字符串");
            }
        }
        if (!pkg.get("evidence").isArray()) {
            errors.add("字段 evidence 必须为数组");
        }
        if (!pkg.get("references").isArray()) {
            errors.add("字段 references 必须为数组");
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 6. 数量/长度限制 + artifact_ref 政策
        ArrayNode evidence = (ArrayNode) pkg.get("evidence");
        if (evidence.size() > maxEvidenceItems) {
            errors.add("evidence 条数 " + evidence.size() + " 超上限 " + maxEvidenceItems);
        }
        for (String field : TEXT_FIELDS) {
            if (pkg.get(field).asText().length() > maxFieldChars) {
                errors.add("字段 " + field + " 超长");
            }
        }
        for (JsonNode ev : evidence) {
            if (!ev.isTextual() || ev.asText().isBlank()) {
                errors.add("evidence 条目必须为非空字符串");
            } else if (ev.asText().length() > maxFieldChars) {
                errors.add("evidence 条目超长");
            }
        }
        for (JsonNode ref : pkg.get("references")) {
            if (!ref.isObject() || !ref.has("artifact_ref") || !ref.get("artifact_ref").isTextual()) {
                errors.add("reference 条目必须含 artifact_ref 字符串");
            } else if (!SAFE_REF.matcher(ref.get("artifact_ref").asText()).matches()) {
                errors.add("artifact_ref 含不允许的外链/凭证: " + truncate(ref.get("artifact_ref").asText()));
            }
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 7. 通过：规范化 package + 脱敏原文
        ObjectNode normalized = mapper.createObjectNode();
        normalized.set("schema_version", pkg.get("schema_version"));
        for (String field : TEXT_FIELDS) {
            normalized.put(field, pkg.get(field).asText());
        }
        normalized.set("evidence", evidence.deepCopy());
        normalized.set("references", ((ArrayNode) pkg.get("references")).deepCopy());
        return new Result(ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                normalized.toString(), redact(body));
    }

    private JsonNode parse(String text) throws Malformed {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new Malformed("JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * BA-14（G0-10 E2E 实证）：DashScope 兼容端点对 openai/deepseek-v3 不强制
     * response_format json_schema strict——模型会把 JSON 裹进 markdown 围栏或前后缀散文。
     * 直接解析失败时做有界候选提取（围栏内容 / 首个 '{' 到最后一个 '}'），
     * 提取后仍走完整 schema 验证链，任何不合规照样 REJECTED_*——提取只是恢复被包裹的合法包，
     * 不是放宽标准。无候选或候选仍非 JSON → REJECTED_MALFORMED（与旧行为一致）。
     */
    private JsonNode parseAnalysis(String analysis) throws Malformed {
        try {
            return mapper.readTree(analysis);
        } catch (Exception original) {
            String candidate = extractJsonCandidate(analysis);
            if (candidate == null) {
                throw new Malformed("JSON 解析失败: " + original.getMessage());
            }
            try {
                return mapper.readTree(candidate);
            } catch (Exception e) {
                throw new Malformed("JSON 解析失败(包裹提取后): " + e.getMessage());
            }
        }
    }

    /** markdown ```json 围栏内容，或无围栏时首 '{' 到末 '}' 的片段；无候选返回 null */
    private String extractJsonCandidate(String analysis) {
        Matcher fence = FENCE.matcher(analysis);
        if (fence.find()) {
            return fence.group(1).trim();
        }
        int start = analysis.indexOf('{');
        int end = analysis.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return analysis.substring(start, end + 1);
        }
        return null;
    }

    /** raw response 入库前脱敏（§6.6 第 6 项；EX-A13 断言目标） */
    public String redact(String raw) {
        if (raw == null) {
            return null;
        }
        String out = raw;
        for (Pattern p : REDACTIONS) {
            out = p.matcher(out).replaceAll("****");
        }
        return out;
    }

    private static String truncate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static final class Malformed extends Exception {
        Malformed(String message) {
            super(message);
        }
    }
}

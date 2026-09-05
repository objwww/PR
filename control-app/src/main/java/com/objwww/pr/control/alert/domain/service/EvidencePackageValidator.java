package com.objwww.pr.control.alert.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
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
 * schema_version 路由（M3-02：v1/v2 显式双版本，未知版本拒绝，禁止猜版本）→
 * 版本内 schema 校验 → 字段数量/长度限制 → redaction 脱敏 → STRUCTURE_VALIDATED。
 *
 * <p>v1 六段式 schema：{schema_version, summary, root_cause(自由文本), evidence[], impact,
 * remediation, references[{artifact_ref}]}——原样保留，语义不变。
 * v2 类型化 schema（AM3 §6.3）：root_cause 换 {component, fault_type, reason_code} +
 * claims[]（类型化断言）；typed 部分的长度/枚举由契约 record 自校验，本类管共享文本字段
 * 限制与 artifact_ref 白名单。references 只允许安全 artifact_ref（prometheus:// /
 * dashboard://），禁止凭证与任意外链（评审修正；AFT-A03 守字段扫描）。
 */
public final class EvidencePackageValidator {

    /** v1/v2 显式路由表：之外的 schema_version 一律 REJECTED_SCHEMA_VERSION */
    private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1, EvidencePackageV2.SCHEMA_VERSION);

    /** 六段式必备字段（references 可为空数组但键必须在；v2 的 root_cause/claims 形状见各自链路） */
    private static final Set<String> REQUIRED_FIELDS =
            Set.of("schema_version", "summary", "root_cause", "evidence", "impact",
                    "remediation", "references");

    private static final Set<String> TEXT_FIELDS =
            Set.of("summary", "root_cause", "impact", "remediation");

    /** v2 下 root_cause 是对象、claims 取代不了六段键位，共享文本只剩这三个 */
    private static final Set<String> V2_TEXT_FIELDS = Set.of("summary", "impact", "remediation");

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
    private final int maxEvidenceItems;
    private final int maxFieldChars;

    public EvidencePackageValidator(int maxResponseBytes, int maxEvidenceItems, int maxFieldChars) {
        if (maxResponseBytes <= 0 || maxEvidenceItems < 1 || maxFieldChars < 1) {
            throw new IllegalArgumentException("验证参数必须为正");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.maxEvidenceItems = maxEvidenceItems;
        this.maxFieldChars = maxFieldChars;
    }

    /** 验证结果：状态 + 拒绝原因链 + 规范化 package + 脱敏后的原文 + 命中版本（v2 附类型化包） */
    public record Result(ValidationStatus status, List<String> errors,
                         String packageJson, String redactedRawText,
                         int schemaVersion, EvidencePackageV2 typedPackage) {

        /** v1 兼容形态（schemaVersion=2 且验证通过时 typedPackage 非空） */
        public Result(ValidationStatus status, List<String> errors,
                      String packageJson, String redactedRawText) {
            this(status, errors, packageJson, redactedRawText, 0, null);
        }
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

        // 4. schema_version 显式路由（禁止猜版本）
        if (!pkg.has("schema_version") || !pkg.get("schema_version").isInt()) {
            throw new Malformed("package 缺整型 schema_version");
        }
        int version = pkg.get("schema_version").asInt();
        if (!SUPPORTED_VERSIONS.contains(version)) {
            errors.add("未知 schema_version=" + version + "，支持 " + SUPPORTED_VERSIONS + "，禁止猜版本");
            return new Result(ValidationStatus.REJECTED_SCHEMA_VERSION, List.copyOf(errors), null, redact(body));
        }
        return version == 1 ? validateV1(pkg, body, errors) : validateV2(pkg, body, errors);
    }

    // ---------------------------------------------------------------- v1（原链路原样保留）

    private Result validateV1(JsonNode pkg, String body, List<String> errors) {
        if (!pkg.isObject()) {
            return malformedV1(body, errors, "analysis 不是 JSON 对象");
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
        if (textLimitViolations(pkg, TEXT_FIELDS, (ArrayNode) pkg.get("evidence"), errors)) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 通过：规范化 package + 脱敏原文
        ObjectNode normalized = mapper.createObjectNode();
        normalized.set("schema_version", pkg.get("schema_version"));
        for (String field : TEXT_FIELDS) {
            normalized.put(field, pkg.get(field).asText());
        }
        normalized.set("evidence", ((ArrayNode) pkg.get("evidence")).deepCopy());
        normalized.set("references", ((ArrayNode) pkg.get("references")).deepCopy());
        return new Result(ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                normalized.toString(), redact(body), 1, null);
    }

    private Result malformedV1(String body, List<String> errors, String message) {
        errors.add(message);
        return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
    }

    // ---------------------------------------------------------------- v2（类型化，AM3 §6.3）

    private Result validateV2(JsonNode pkg, String body, List<String> errors) {
        if (!pkg.isObject()) {
            return malformedV1(body, errors, "analysis 不是 JSON 对象");
        }
        for (String field : REQUIRED_FIELDS) {
            if (!pkg.has(field)) {
                errors.add("缺少字段: " + field);
            }
        }
        if (pkg.has("root_cause") && !pkg.get("root_cause").isObject()) {
            errors.add("v2 root_cause 必须为对象{component,fault_type,reason_code}");
        }
        if (pkg.has("claims") && !pkg.get("claims").isArray()) {
            errors.add("字段 claims 必须为数组");
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        for (String field : V2_TEXT_FIELDS) {
            if (!pkg.get(field).isTextual() || pkg.get(field).asText().isBlank()) {
                errors.add("字段 " + field + " 必须为非空字符串");
            }
        }
        if (!pkg.get("evidence").isArray() || !pkg.get("references").isArray()) {
            errors.add("字段 evidence/references 必须为数组");
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        if (textLimitViolations(pkg, V2_TEXT_FIELDS, (ArrayNode) pkg.get("evidence"), errors)) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 类型化契约自校验（长度/枚举/嵌套形状）；形状错误进拒绝原因链
        EvidencePackageV2 typed;
        try {
            typed = EvidencePackageV2.fromJson(pkg);
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add("v2 类型化契约违规: " + e.getMessage());
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 通过：规范化 package（保留 typed 字段）+ 脱敏原文
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("schema_version", EvidencePackageV2.SCHEMA_VERSION);
        normalized.put("summary", pkg.get("summary").asText());
        normalized.set("root_cause", pkg.get("root_cause").deepCopy());
        normalized.set("claims", ((ArrayNode) pkg.get("claims")).deepCopy());
        normalized.set("evidence", ((ArrayNode) pkg.get("evidence")).deepCopy());
        normalized.put("impact", pkg.get("impact").asText());
        normalized.put("remediation", pkg.get("remediation").asText());
        normalized.set("references", ((ArrayNode) pkg.get("references")).deepCopy());
        return new Result(ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                normalized.toString(), redact(body), EvidencePackageV2.SCHEMA_VERSION, typed);
    }

    // ---------------------------------------------------------------- 共享政策（限长 + artifact_ref 白名单）

    /** 共享文本字段限长 + evidence 条目限制 + references 白名单；有违规写进 errors 返回 true */
    private boolean textLimitViolations(JsonNode pkg, Set<String> textFields,
                                        ArrayNode evidence, List<String> errors) {
        boolean violated = false;
        if (evidence.size() > maxEvidenceItems) {
            errors.add("evidence 条数 " + evidence.size() + " 超上限 " + maxEvidenceItems);
            violated = true;
        }
        for (String field : textFields) {
            if (pkg.get(field).isTextual() && pkg.get(field).asText().length() > maxFieldChars) {
                errors.add("字段 " + field + " 超长");
                violated = true;
            }
        }
        for (JsonNode ev : evidence) {
            if (!ev.isTextual() || ev.asText().isBlank()) {
                errors.add("evidence 条目必须为非空字符串");
                violated = true;
            } else if (ev.asText().length() > maxFieldChars) {
                errors.add("evidence 条目超长");
                violated = true;
            }
        }
        for (JsonNode ref : pkg.get("references")) {
            if (!ref.isObject() || !ref.has("artifact_ref") || !ref.get("artifact_ref").isTextual()) {
                errors.add("reference 条目必须含 artifact_ref 字符串");
                violated = true;
            } else if (!SAFE_REF.matcher(ref.get("artifact_ref").asText()).matches()) {
                errors.add("artifact_ref 含不允许的外链/凭证: " + truncate(ref.get("artifact_ref").asText()));
                violated = true;
            }
        }
        return violated;
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

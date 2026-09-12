package com.objwww.pr.control.alert.domain.service;

import com.objwww.pr.control.alert.application.EvidencePackageJsonCodec;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>BA-22（R3 domain 零框架）：本类零 Jackson——解析/写出经
 * {@link EvidencePackageJsonCodec}（application 层，唯一 Jackson 触点），内部全程
 * 中立树（Map/List/String/Number/Boolean/null）。规范化输出由 ObjectNode.toString()
 * （输入键序）改为 canonical 字典序——前置检查确认无下游对 packageJson 做 digest
 * 对账（raw/payload digest 只对原文），键序漂移安全。
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
        Object outerTree = parseTree(body);
        if (!(outerTree instanceof Map<?, ?> outer)
                || !(outer.get("analysis") instanceof String analysis)) {
            throw new Malformed("Holmes 外层响应缺 analysis 字符串字段");
        }

        // 3. analysis 内嵌 JSON 字符串（BA-14 包裹提取面）
        Object pkgTree = parseAnalysis(analysis);

        // 4. schema_version 显式路由（禁止猜版本）——非对象/缺键/非整型都落本检查
        //（与旧链一致：ArrayNode.has=false、文本 "2" isInt=false 均为 Malformed）
        if (!(pkgTree instanceof Map<?, ?> pkg)
                || !(pkg.get("schema_version") instanceof Integer version)) {
            throw new Malformed("package 缺整型 schema_version");
        }
        if (!SUPPORTED_VERSIONS.contains(version)) {
            errors.add("未知 schema_version=" + version + "，支持 " + SUPPORTED_VERSIONS + "，禁止猜版本");
            return new Result(ValidationStatus.REJECTED_SCHEMA_VERSION, List.copyOf(errors), null, redact(body));
        }
        return version == 1 ? validateV1(pkg, body, errors) : validateV2(pkg, body, errors);
    }

    // ---------------------------------------------------------------- v1（原链路原样保留）

    private Result validateV1(Map<?, ?> pkg, String body, List<String> errors) {
        for (String field : REQUIRED_FIELDS) {
            if (!pkg.containsKey(field)) {
                errors.add("缺少字段: " + field);
            }
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        for (String field : TEXT_FIELDS) {
            if (!(pkg.get(field) instanceof String text) || text.isBlank()) {
                errors.add("字段 " + field + " 必须为非空字符串");
            }
        }
        if (!(pkg.get("evidence") instanceof List<?>)) {
            errors.add("字段 evidence 必须为数组");
        }
        if (!(pkg.get("references") instanceof List<?>)) {
            errors.add("字段 references 必须为数组");
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        if (textLimitViolations(pkg, TEXT_FIELDS, pkg.get("evidence"), errors)) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 通过：规范化 package（canonical 字典序）+ 脱敏原文
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("schema_version", pkg.get("schema_version"));
        for (String field : TEXT_FIELDS) {
            normalized.put(field, pkg.get(field));
        }
        normalized.put("evidence", pkg.get("evidence"));
        normalized.put("references", pkg.get("references"));
        return new Result(ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                EvidencePackageJsonCodec.writeCanonical(normalized), redact(body), 1, null);
    }

    // ---------------------------------------------------------------- v2（类型化，AM3 §6.3）

    private Result validateV2(Map<?, ?> pkg, String body, List<String> errors) {
        for (String field : REQUIRED_FIELDS) {
            if (!pkg.containsKey(field)) {
                errors.add("缺少字段: " + field);
            }
        }
        if (pkg.containsKey("root_cause") && !(pkg.get("root_cause") instanceof Map<?, ?>)) {
            errors.add("v2 root_cause 必须为对象{component,fault_type,reason_code}");
        }
        if (pkg.containsKey("claims") && !(pkg.get("claims") instanceof List<?>)) {
            errors.add("字段 claims 必须为数组");
        }
        if (!errors.isEmpty()) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        for (String field : V2_TEXT_FIELDS) {
            if (!(pkg.get(field) instanceof String text) || text.isBlank()) {
                errors.add("字段 " + field + " 必须为非空字符串");
            }
        }
        if (!(pkg.get("evidence") instanceof List<?>) || !(pkg.get("references") instanceof List<?>)) {
            errors.add("字段 evidence/references 必须为数组");
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }
        if (textLimitViolations(pkg, V2_TEXT_FIELDS, pkg.get("evidence"), errors)) {
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 类型化契约自校验（长度/枚举/嵌套形状）；形状错误进拒绝原因链
        EvidencePackageV2 typed;
        try {
            typed = EvidencePackageV2.fromMap(castObjectMap(pkg));
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add("v2 类型化契约违规: " + e.getMessage());
            return new Result(ValidationStatus.REJECTED_SCHEMA_MISMATCH, List.copyOf(errors), null, redact(body));
        }

        // 通过：规范化 package（保留 typed 字段；canonical 字典序）+ 脱敏原文
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("schema_version", EvidencePackageV2.SCHEMA_VERSION);
        normalized.put("summary", pkg.get("summary"));
        normalized.put("root_cause", pkg.get("root_cause"));
        normalized.put("claims", pkg.get("claims"));
        normalized.put("evidence", pkg.get("evidence"));
        normalized.put("impact", pkg.get("impact"));
        normalized.put("remediation", pkg.get("remediation"));
        normalized.put("references", pkg.get("references"));
        return new Result(ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                EvidencePackageJsonCodec.writeCanonical(normalized), redact(body),
                EvidencePackageV2.SCHEMA_VERSION, typed);
    }

    // ---------------------------------------------------------------- 共享政策（限长 + artifact_ref 白名单）

    /** 共享文本字段限长 + evidence 条目限制 + references 白名单；有违规写进 errors 返回 true */
    private boolean textLimitViolations(Map<?, ?> pkg, Set<String> textFields,
                                        Object evidenceObj, List<String> errors) {
        List<?> evidence = (List<?>) evidenceObj;
        boolean violated = false;
        if (evidence.size() > maxEvidenceItems) {
            errors.add("evidence 条数 " + evidence.size() + " 超上限 " + maxEvidenceItems);
            violated = true;
        }
        for (String field : textFields) {
            if (pkg.get(field) instanceof String text && text.length() > maxFieldChars) {
                errors.add("字段 " + field + " 超长");
                violated = true;
            }
        }
        for (Object ev : evidence) {
            if (!(ev instanceof String text) || text.isBlank()) {
                errors.add("evidence 条目必须为非空字符串");
                violated = true;
            } else if (text.length() > maxFieldChars) {
                errors.add("evidence 条目超长");
                violated = true;
            }
        }
        for (Object refObj : (List<?>) pkg.get("references")) {
            if (!(refObj instanceof Map<?, ?> ref) || !(ref.get("artifact_ref") instanceof String refText)) {
                errors.add("reference 条目必须含 artifact_ref 字符串");
                violated = true;
            } else if (!SAFE_REF.matcher(refText).matches()) {
                errors.add("artifact_ref 含不允许的外链/凭证: " + truncate(refText));
                violated = true;
            }
        }
        return violated;
    }

    /** JSON 文本 → 中立树；解析失败转 Malformed，消息与旧链一致（"JSON 解析失败: …"） */
    private Object parseTree(String text) throws Malformed {
        try {
            return EvidencePackageJsonCodec.readTree(text);
        } catch (IllegalArgumentException e) {
            throw new Malformed(e.getMessage());
        }
    }

    /**
     * BA-14（G0-10 E2E 实证）：DashScope 兼容端点对 openai/deepseek-v3 不强制
     * response_format json_schema strict——模型会把 JSON 裹进 markdown 围栏或前后缀散文。
     * 直接解析失败时做有界候选提取（围栏内容 / 首个 '{' 到最后一个 '}'），
     * 提取后仍走完整 schema 验证链，任何不合规照样 REJECTED_*——提取只是恢复被包裹的合法包，
     * 不是放宽标准。无候选或候选仍非 JSON → REJECTED_MALFORMED（与旧行为一致）。
     */
    private Object parseAnalysis(String analysis) throws Malformed {
        try {
            return parseTree(analysis);
        } catch (Malformed original) {
            String candidate = extractJsonCandidate(analysis);
            if (candidate == null) {
                throw original;
            }
            try {
                return parseTree(candidate);
            } catch (Malformed e) {
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

    private static Map<String, Object> castObjectMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
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

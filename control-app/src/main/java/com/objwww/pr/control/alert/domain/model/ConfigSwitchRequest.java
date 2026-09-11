package com.objwww.pr.control.alert.domain.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 运行中热更新切换诉求（EN-04，§227 命令字段的类型化面）：随 CONFIG_SWITCH 命令行
 * 的 payload 携带（operator_command 表零新增列——正文列落库后零开口的既有契约），
 * 应用事务从这里取 expectedConfigEpoch/目标 digest 做 CAS 与兼容核验。
 *
 * <p><b>白名单纪律（H12 超范围变更拒绝）</b>：payload 只认本类型五个键，预算/期限
 * 扩展类键（*_budget、extend_* 等）一律拒绝——切换不借道抬高总额或延长 deadline
 * （§231：已用和预留不清零不退款，新约束只能在剩余额度内收紧）。deadline 是命令
 * 自身的等待安全点期限（§227），不是 Run 的期限。
 */
public record ConfigSwitchRequest(long expectedRevision,
                                  long expectedConfigEpoch,
                                  String targetReleaseDigest,
                                  String reason,
                                  Instant deadline) {

    /** payload 键白名单（§227 命令字段——切换专有四键 + 修订锚镜像） */
    private static final Set<String> PAYLOAD_KEYS = Set.of(
            "expected_revision", "expected_config_epoch", "target_release_digest",
            "reason", "deadline");

    private static final Pattern DIGEST_HEX = Pattern.compile("[0-9a-f]{64}");

    public ConfigSwitchRequest {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负");
        }
        if (expectedConfigEpoch < 0) {
            throw new IllegalArgumentException("expectedConfigEpoch 不能为负");
        }
        if (targetReleaseDigest == null || !DIGEST_HEX.matcher(targetReleaseDigest)
                .matches()) {
            throw new IllegalArgumentException(
                    "targetReleaseDigest 必须 64 位小写 hex: " + targetReleaseDigest);
        }
        if (reason == null || reason.isBlank() || reason.length() > 512) {
            throw new IllegalArgumentException("reason 必填且 ≤512 字符");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("deadline 必填（§227：等待安全点的命令期限）");
        }
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expected_revision", expectedRevision);
        payload.put("expected_config_epoch", expectedConfigEpoch);
        payload.put("target_release_digest", targetReleaseDigest);
        payload.put("reason", reason);
        payload.put("deadline", deadline.toString());
        return payload;
    }

    /** payload → 类型化诉求；白名单外键即拒绝（H12：预算/期限扩展混入 = 超范围变更） */
    public static ConfigSwitchRequest fromPayload(Map<String, Object> payload) {
        for (String key : payload.keySet()) {
            if (!PAYLOAD_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "切换 payload 含超范围变更键（H12：预算/期限等诉求不在切换面）: "
                                + key);
            }
        }
        long expectedConfigEpoch = requireLong(payload, "expected_config_epoch");
        String digest = requireText(payload, "target_release_digest");
        String reason = requireText(payload, "reason");
        Instant deadline;
        try {
            deadline = Instant.parse(requireText(payload, "deadline"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("deadline 必须 ISO-8601: "
                    + payload.get("deadline"), e);
        }
        return new ConfigSwitchRequest(
                payload.get("expected_revision") instanceof Number n ? n.longValue() : 0L,
                expectedConfigEpoch, digest, reason, deadline);
    }

    private static long requireLong(Map<String, Object> payload, String key) {
        if (!(payload.get(key) instanceof Number n)) {
            throw new IllegalArgumentException("切换 payload 缺数值键: " + key);
        }
        return n.longValue();
    }

    private static String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("切换 payload 缺文本键: " + key);
        }
        return text;
    }
}

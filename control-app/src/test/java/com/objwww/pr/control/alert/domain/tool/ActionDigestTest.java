package com.objwww.pr.control.alert.domain.tool;

import com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UT-AM4-15b：ActionDigest——sha256(canonicalize(envelope)) 纯函数。
 * envelope 整体规范化，无手工分隔符拼接；canonicalizationVersion 恒为 internal-v1。
 *
 * <p>EX-A0：envelope 输入身份组件换型 {@link InvestigationInputDigest}；canonical
 * wire 键冻结为 v1 名 inputSnapshotDigest（EX-A0 契约 §3.4，改名须升版另开评审）。
 */
class ActionDigestTest {

    private static final InvestigationInputDigest SNAP =
            new InvestigationInputDigest("ab".repeat(32));
    private static final InvestigationInputDigest SNAP_XYZ =
            new InvestigationInputDigest("cd".repeat(32));

    private static Map<String, Object> args() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("query", "rate(http_errors_total[5m])");
        m.put("from", "2026-09-05T00:00:00Z");
        return m;
    }

    private static ActionEnvelope envelope() {
        return new ActionEnvelope("metrics", "prometheus_query", "1.0.0", "v1",
                args(), "2026-09-05T00:00:00Z/1h", SNAP);
    }

    @Test
    void sameEnvelopeSameDigest() {
        assertThat(ActionDigest.of(envelope())).isEqualTo(ActionDigest.of(envelope()));
    }

    @Test
    void argFieldOrderDoesNotChangeDigest() {
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("from", "2026-09-05T00:00:00Z");
        reordered.put("query", "rate(http_errors_total[5m])");
        ActionEnvelope shuffled = new ActionEnvelope("metrics", "prometheus_query", "1.0.0",
                "v1", reordered, "2026-09-05T00:00:00Z/1h", SNAP);
        assertThat(ActionDigest.of(shuffled)).isEqualTo(ActionDigest.of(envelope()));
    }

    /** envelope 每个字段逐一变化 → 摘要必变（矩阵） */
    @Test
    void everyEnvelopeFieldChangeChangesDigest() {
        String base = ActionDigest.of(envelope());
        ActionEnvelope baseEnv = envelope();

        assertThat(ActionDigest.of(new ActionEnvelope("logs", baseEnv.toolName(),
                baseEnv.toolVersion(), baseEnv.schemaVersion(), baseEnv.args(),
                baseEnv.timeRange(), baseEnv.investigationInputDigest())))
                .as("toolNamespace 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                "prometheus_query_range", baseEnv.toolVersion(), baseEnv.schemaVersion(),
                baseEnv.args(), baseEnv.timeRange(), baseEnv.investigationInputDigest())))
                .as("toolName 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), "2.0.0", baseEnv.schemaVersion(), baseEnv.args(),
                baseEnv.timeRange(), baseEnv.investigationInputDigest())))
                .as("toolVersion 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), baseEnv.toolVersion(), "v2", baseEnv.args(),
                baseEnv.timeRange(), baseEnv.investigationInputDigest())))
                .as("schemaVersion 变化").isNotEqualTo(base);
        Map<String, Object> changedArgs = new LinkedHashMap<>(args());
        changedArgs.put("query", "rate(http_errors_total[15m])");
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), baseEnv.toolVersion(), baseEnv.schemaVersion(),
                changedArgs, baseEnv.timeRange(), baseEnv.investigationInputDigest())))
                .as("args 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), baseEnv.toolVersion(), baseEnv.schemaVersion(),
                baseEnv.args(), "2026-09-05T01:00:00Z/1h",
                baseEnv.investigationInputDigest())))
                .as("timeRange 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), baseEnv.toolVersion(), baseEnv.schemaVersion(),
                baseEnv.args(), baseEnv.timeRange(), SNAP_XYZ)))
                .as("investigationInputDigest 变化").isNotEqualTo(base);
        assertThat(ActionDigest.of(new ActionEnvelope(baseEnv.toolNamespace(),
                baseEnv.toolName(), baseEnv.toolVersion(), baseEnv.schemaVersion(),
                baseEnv.args(), baseEnv.timeRange(), null)))
                .as("investigationInputDigest null 与有值不同").isNotEqualTo(base);
    }

    @Test
    void digestMatchesDirectCanonicalizationOfEnvelope() {
        // 无手工拼接：digest == sha256(canonicalize(envelope 字段 Map + canonicalizationVersion))
        // wire 键冻结为 v1 名 inputSnapshotDigest（EX-A0 §3.4）
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("toolNamespace", "metrics");
        expected.put("toolName", "prometheus_query");
        expected.put("toolVersion", "1.0.0");
        expected.put("schemaVersion", "v1");
        expected.put("canonicalArgs", args());
        expected.put("timeRange", "2026-09-05T00:00:00Z/1h");
        expected.put("inputSnapshotDigest", SNAP.hex());
        expected.put("canonicalizationVersion", "internal-v1");
        assertThat(ActionDigest.of(envelope()))
                .isEqualTo(InternalCanonicalJsonV1.sha256(expected));
    }

    @Test
    void digestIs64LowerHex() {
        assertThat(ActionDigest.of(envelope())).matches("[0-9a-f]{64}");
    }

    /** envelope 契约校验：空/blank 关键字段逐一拒绝（矩阵） */
    @Test
    void envelopeRejectsBlankRequiredFields() {
        ActionEnvelope base = envelope();
        String[][] cases = {
                {null, base.toolName(), base.toolVersion(), base.schemaVersion(), base.timeRange()},
                {" ", base.toolName(), base.toolVersion(), base.schemaVersion(), base.timeRange()},
                {base.toolNamespace(), null, base.toolVersion(), base.schemaVersion(), base.timeRange()},
                {base.toolNamespace(), base.toolName(), "", base.schemaVersion(), base.timeRange()},
                {base.toolNamespace(), base.toolName(), base.toolVersion(), "  ", base.timeRange()},
                {base.toolNamespace(), base.toolName(), base.toolVersion(), base.schemaVersion(), null},
        };
        for (String[] c : cases) {
            assertThatThrownBy(() -> new ActionEnvelope(c[0], c[1], c[2], c[3],
                    args(), c[4], null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nullArgsAndNullInvestigationInputDigestAreAllowed() {
        ActionEnvelope noArgs = new ActionEnvelope("metrics", "ping", "1.0.0", "v1",
                null, "2026-09-05T00:00:00Z/1h", null);
        assertThat(ActionDigest.of(noArgs)).matches("[0-9a-f]{64}");
    }

    @Test
    void investigationInputDigestMustBeHex64() {
        // EX-A0：身份类型即守卫——非 64 位小写 hex 构造期拒绝（混用/脏值进不了身份面）
        assertThatThrownBy(() -> new InvestigationInputDigest("snap-abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

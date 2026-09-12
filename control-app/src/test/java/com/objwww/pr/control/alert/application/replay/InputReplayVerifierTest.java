package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.domain.agent.RcaModelInputReplayPort;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 回放核验单测（MC35/MC36 面，零库假件）：FULL 完整回放/篡改检测/账本对账，
 * REDACTED 与 DIGEST_ONLY 结构性不完整（不伪称可回放），role_digest 版本反查。
 */
class InputReplayVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
    private static final String ROLE_PROMPT = "你是主调查 Agent。";

    private ReplayStoreFake inputs;
    private AssetStoreFake assets;
    private InputReplayVerifier verifier;

    private final UUID modelCallId = UUID.randomUUID();
    private final String roleDigest = "a".repeat(64);

    @BeforeEach
    void setUp() {
        inputs = new ReplayStoreFake();
        assets = new AssetStoreFake();
        verifier = new InputReplayVerifier(inputs, assets);
    }

    private String fullPrompt() {
        return ROLE_PROMPT + "\n{\"task\":\"...\"}";
    }

    private void seedFullRow(String promptText, String captureDigest) {
        inputs.row = new RcaModelInputReplayPort.ReplayRow(modelCallId, "FULL",
                promptText, captureDigest, captureDigest, "primary", roleDigest);
    }

    private void seedRoleAsset() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages_template", ROLE_PROMPT);
        content.put("variables_schema", List.of("task_envelope"));
        content.put("role", "primary");
        content.put("role_version", "1");
        content.put("role_digest", roleDigest);
        assets.assets.add(ReleaseAsset.of(ReleaseAsset.KIND_PROMPT, content,
                "test", NOW));
    }

    // ------------------------------------------------------------- MC35 完整回放

    @Test
    void mc35_full档_原文复算摘要一致_角色快照可反查_完整回放() {
        seedRoleAsset();
        seedFullRow(fullPrompt(), Digest.sha256Of(fullPrompt()).value());

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.digestMatch()).isTrue();
        assertThat(verdict.rehashMatch()).isTrue();
        assertThat(verdict.roleSnapshotResolved()).as("MC36 版本反查命中").isTrue();
        assertThat(verdict.complete()).isTrue();
        assertThat(verdict.note()).isEqualTo("完整回放");
    }

    @Test
    void mc35_full档原文被篡改_复算不一致_判存储损坏() {
        seedRoleAsset();
        String intact = fullPrompt();
        seedFullRow(intact + "（被改）", Digest.sha256Of(intact).value());

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.digestMatch()).isTrue();
        assertThat(verdict.rehashMatch()).as("复算抓出篡改").isFalse();
        assertThat(verdict.complete()).isFalse();
    }

    @Test
    void mc35捕获摘要与账本摘要不一致_判对账断裂() {
        seedFullRow(fullPrompt(), Digest.sha256Of(fullPrompt()).value());
        inputs.row = new RcaModelInputReplayPort.ReplayRow(modelCallId, "FULL",
                fullPrompt(), Digest.sha256Of(fullPrompt()).value(),
                "b".repeat(64), "primary", roleDigest);

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.digestMatch()).as("捕获↔账本摘要不一致").isFalse();
        assertThat(verdict.complete()).isFalse();
    }

    // ------------------------------------------------------------- 诚实不完整面

    @Test
    void mc35_redacted档_摘要对账一致但原文结构性不完整() {
        seedRoleAsset();
        String masked = "掩文 masked=2";
        inputs.row = new RcaModelInputReplayPort.ReplayRow(modelCallId, "REDACTED",
                masked, Digest.sha256Of("原文").value(), Digest.sha256Of("原文").value(),
                "primary", roleDigest);

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.digestMatch()).isTrue();
        assertThat(verdict.complete()).as("掩文不可反推——不伪称完整").isFalse();
        assertThat(verdict.note()).contains("REDACTED");
    }

    @Test
    void mc35_digestOnly档_零原文_仅摘要对账不可回放() {
        inputs.row = new RcaModelInputReplayPort.ReplayRow(modelCallId, "DIGEST_ONLY",
                null, "c".repeat(64), "c".repeat(64), "primary", roleDigest);

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.digestMatch()).isTrue();
        assertThat(verdict.complete()).isFalse();
        assertThat(verdict.note()).contains("DIGEST_ONLY");
    }

    @Test
    void 无捕获行_判不可回放() {
        InputReplayVerifier.Verdict verdict = verifier.verify(UUID.randomUUID());

        assertThat(verdict.complete()).isFalse();
        assertThat(verdict.note()).contains("无捕获行");
    }

    // ------------------------------------------------------------- MC36 版本反查

    @Test
    void mc36_role快照缺失_版本面未验_输入链完整仍成立() {
        String prompt = fullPrompt();
        seedFullRow(prompt, Digest.sha256Of(prompt).value());

        InputReplayVerifier.Verdict verdict = verifier.verify(modelCallId);

        assertThat(verdict.roleSnapshotResolved()).as("资产缺登记→反查不中").isFalse();
        assertThat(verdict.complete()).as("输入摘要链不受角色快照缺失影响").isTrue();
    }

    @Test
    void mc36_role_digest不匹配的资产_反查不串版本() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("messages_template", "旧版 prompt");
        content.put("variables_schema", List.of("task_envelope"));
        content.put("role", "primary");
        content.put("role_version", "0");
        content.put("role_digest", "f".repeat(64));
        assets.assets.add(ReleaseAsset.of(ReleaseAsset.KIND_PROMPT, content, "test", NOW));
        seedRoleAsset();
        seedFullRow(fullPrompt(), Digest.sha256Of(fullPrompt()).value());

        assertThat(verifier.rolePromptByDigest(roleDigest)).contains(ROLE_PROMPT);
        assertThat(verifier.rolePromptByDigest("f".repeat(64))).contains("旧版 prompt");
    }

    // ------------------------------------------------------------- 夹具

    private static final class ReplayStoreFake implements RcaModelInputReplayPort {
        ReplayRow row;

        @Override
        public Optional<ReplayRow> byModelCallId(UUID modelCallId) {
            return row != null && row.modelCallId().equals(modelCallId)
                    ? Optional.of(row) : Optional.empty();
        }
    }

    private static final class AssetStoreFake implements ReleaseAssetRepository {
        final List<ReleaseAsset> assets = new ArrayList<>();

        @Override
        public boolean insert(ReleaseAsset asset) {
            assets.add(asset);
            return true;
        }

        @Override
        public Optional<ReleaseAsset> findByDigest(String kind, Digest digest) {
            return Optional.empty();
        }

        @Override
        public List<ReleaseAsset> listRecent(String kind, int limit) {
            return assets.stream().filter(a -> a.kind().equals(kind)).limit(limit).toList();
        }
    }
}

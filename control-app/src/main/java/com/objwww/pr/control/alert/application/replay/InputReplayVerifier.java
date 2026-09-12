package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.domain.agent.RcaModelInputReplayPort;
import com.objwww.pr.control.release.domain.model.ReleaseAsset;
import com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository;
import com.objwww.pr.shared.Digest;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R2 回放核验（MC35/MC36，评测/审计面）：捕获行 + 账本行的摘要对账与原文复算，
 * role 快照反查。<b>诚实纪律</b>：FULL 档才可能"完整回放"；REDACTED（掩文不可反推）
 * 与 DIGEST_ONLY（零原文）结构性不完整——digestMatch 只证"对账一致"，complete()
 * 不伪称真；角色快照缺失不影响输入摘要链的成立（version 反查是独立分面）。
 */
public class InputReplayVerifier {

    /** role_digest → prompt 原文反查的快照扫描窗（release_asset PROMPT 最近 200 条） */
    private static final int ROLE_ASSET_SCAN_LIMIT = 200;

    private final RcaModelInputReplayPort inputs;
    private final ReleaseAssetRepository assets;

    public InputReplayVerifier(RcaModelInputReplayPort inputs, ReleaseAssetRepository assets) {
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    /**
     * 回放判定四分面：digestMatch = 捕获摘要 ↔ 账本摘要一致（对账链未断）；
     * rehashMatch = 原文复算 sha256 与捕获摘要一致（存储未损坏/未篡改）；
     * roleSnapshotResolved = role_digest 反查得到 PROMPT 快照且为捕获原文前缀
     * （角色版本未漂移）；complete = digestMatch && rehashMatch（FULL 档完整回放）。
     */
    public Verdict verify(UUID modelCallId) {
        Optional<RcaModelInputReplayPort.ReplayRow> row =
                inputs.byModelCallId(modelCallId);
        if (row.isEmpty()) {
            return new Verdict(false, false, false, "无捕获行：该调用未落输入档，不可回放");
        }
        RcaModelInputReplayPort.ReplayRow r = row.get();
        boolean digestMatch = r.captureDigest() != null
                && r.captureDigest().equals(r.ledgerPromptDigest());

        boolean rehashMatch = false;
        boolean roleResolved = false;
        String note;
        switch (r.captureLevel()) {
            case "FULL" -> {
                if (r.promptText() == null) {
                    note = "FULL 档原文缺失（异常态，存储面损坏）";
                } else {
                    rehashMatch = Digest.sha256Of(r.promptText()).value()
                            .equals(r.captureDigest());
                    note = rehashMatch ? "完整回放" : "FULL 原文复算不一致：存储损坏或被篡改";
                }
            }
            case "REDACTED" -> note =
                    "REDACTED：脱敏文不可反推原文，摘要对账一致但回放结构性不完整";
            case "DIGEST_ONLY" -> note =
                    "DIGEST_ONLY：零原文落档，仅摘要对账（回放按设计不完整）";
            default -> note = "未知捕获档位：" + r.captureLevel();
        }

        if (r.roleDigest() != null) {
            Optional<String> rolePrompt = rolePromptByDigest(r.roleDigest());
            if (rolePrompt.isPresent() && r.promptText() != null
                    && r.promptText().startsWith(rolePrompt.get())) {
                roleResolved = true;
            }
        }
        return new Verdict(digestMatch, rehashMatch, roleResolved, note);
    }

    /**
     * MC36 role 快照版本反查：role_digest（AgentProfile.digest，整档案哈希）精确匹配
     * PROMPT 快照 content.role_digest → messages_template。不匹配版本不串（旧版
     * 快照只回旧版 role_digest 的查询）。
     */
    public Optional<String> rolePromptByDigest(String roleDigest) {
        for (ReleaseAsset asset : assets.listRecent(ReleaseAsset.KIND_PROMPT,
                ROLE_ASSET_SCAN_LIMIT)) {
            Object digest = asset.content().get("role_digest");
            if (roleDigest.equals(digest)) {
                Object template = asset.content().get("messages_template");
                return template instanceof String s ? Optional.of(s) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 回放判定（四分面 + 人读 note；complete 只由摘要链两面构成） */
    public record Verdict(boolean digestMatch, boolean rehashMatch,
                          boolean roleSnapshotResolved, String note) {

        /** 完整回放成立 = 对账一致 + 原文复算一致（角色快照反查是独立分面，不影响） */
        public boolean complete() {
            return digestMatch && rehashMatch;
        }
    }
}

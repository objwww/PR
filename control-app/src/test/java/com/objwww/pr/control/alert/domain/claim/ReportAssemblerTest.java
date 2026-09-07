package com.objwww.pr.control.alert.domain.claim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-AM4-23：ReportAssembler——只从冻结 Snapshot + 已裁决 Claim 组装（无 LLM 输入口）；
 * 分节依据来自裁决状态机：确定节 = corroborated（TRUE=确认根因候选/FALSE=确认排除），
 * 推测节 = SINGLE_SOURCE，未决节 = UNKNOWN；无证据（无冻结快照绑定）不产确认根因；
 * 推测不冒充确认（单源 TRUE 也只进推测节）；PARTIAL = 有确认根因但存在未决/推测残留；
 * UNRESOLVED = 无确认根因；外来快照的 Claim 被排除且不污染分节。
 */
class ReportAssemblerTest {

    private static ClaimVerdict corroboratedTrue(String key) {
        return new ClaimVerdict(key, "scope", "tr", 0, "snap-1",
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("a", "b"), "r", List.of("ev-1", "ev-2"), "policy-v1");
    }

    private static ClaimVerdict corroboratedFalse(String key) {
        return new ClaimVerdict(key, "scope", "tr", 0, "snap-1",
                ClaimStatus.FALSE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("a", "b"), "r", List.of("ev-3"), "policy-v1");
    }

    private static ClaimVerdict singleSourceTrue(String key) {
        return new ClaimVerdict(key, "scope", "tr", 0, "snap-1",
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE,
                List.of("a"), "r", List.of("ev-4"), "policy-v1");
    }

    private static ClaimVerdict conflictUnknown(String key) {
        return new ClaimVerdict(key, "scope", "tr", 0, "snap-1",
                ClaimStatus.UNKNOWN, EvidenceBasis.MULTI_SOURCE_CONFLICT,
                List.of("a", "b"), "r", List.of("ev-5", "ev-6"), "policy-v1");
    }

    // ---------------- 无证据不产根因 ----------------

    @Test
    void 无claim_UNRESOLVED且确定节为空() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1", List.of());
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.UNRESOLVED);
        assertThat(report.confirmed()).isEmpty();
        assertThat(report.snapshotDigest()).isEqualTo("snap-1");
    }

    @Test
    void 无冻结快照绑定_装配显式拒绝() {
        // 无证据 = 无冻结快照可绑定：fail-closed 拒绝装配（诚实空报告也有快照 digest；
        // 绝不在无呈堂基础的报告里产确认根因或臆测分节）
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ReportAssembler.assemble(null, List.of(corroboratedTrue("k"))))
                .withMessageContaining("冻结快照");
    }

    // ---------------- 分节依据 = 裁决状态机（非 LLM 自述） ----------------

    @Test
    void 全部双源一致且无残留_CONFIRMED() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(corroboratedTrue("k1"), corroboratedFalse("k2")));
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.CONFIRMED);
        assertThat(report.confirmed()).extracting(ClaimVerdict::claimKey)
                .containsExactly("k1", "k2");
        assertThat(report.hasConfirmedRootCause()).isTrue(); // corroborated TRUE 在确定节
    }

    @Test
    void 单源TRUE只进推测节_推测不冒充确认() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(singleSourceTrue("k")));
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.UNRESOLVED);
        assertThat(report.confirmed()).isEmpty();
        assertThat(report.speculative()).extracting(ClaimVerdict::claimKey)
                .containsExactly("k");
    }

    @Test
    void 冲突UNKNOWN进未决节() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(conflictUnknown("k")));
        assertThat(report.unresolved()).extracting(ClaimVerdict::claimKey).containsExactly("k");
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.UNRESOLVED);
    }

    // ---------------- PARTIAL / UNRESOLVED ----------------

    @Test
    void 有确认根因但有推测残留_PARTIAL() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(corroboratedTrue("root"), singleSourceTrue("side")));
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.PARTIAL);
        assertThat(report.hasConfirmedRootCause()).isTrue();
    }

    @Test
    void 有确认根因但有未决残留_PARTIAL() {
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(corroboratedTrue("root"), conflictUnknown("other")));
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.PARTIAL);
    }

    @Test
    void 只有确认排除无双源TRUE_UNRESOLVED() {
        // 确认证伪不是根因：只有 corroborated FALSE → 无确认根因
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(corroboratedFalse("k")));
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.UNRESOLVED);
        assertThat(report.hasConfirmedRootCause()).isFalse();
        assertThat(report.confirmed()).extracting(ClaimVerdict::claimKey).containsExactly("k");
    }

    // ---------------- 快照绑定面 ----------------

    @Test
    void 外来快照的claim被排除不污染分节() {
        ClaimVerdict foreign = new ClaimVerdict("foreign", "scope", "tr", 0, "snap-OTHER",
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("a", "b"), "r", List.of("ev-x"), "policy-v1");
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(corroboratedTrue("k"), foreign));
        assertThat(report.excludedForeignSnapshotCount()).isEqualTo(1);
        assertThat(report.confirmed()).extracting(ClaimVerdict::claimKey).containsExactly("k");
        assertThat(report.outcome()).isEqualTo(ReportAssembler.Outcome.CONFIRMED);
    }

    @Test
    void 无快照约束的claim_随报告快照组装() {
        // snapshotDigest=null 的断言不依赖快照约束，仍属本 run 事实
        ClaimVerdict free = new ClaimVerdict("free", "scope", "tr", 0, null,
                ClaimStatus.TRUE, EvidenceBasis.MULTI_SOURCE_CONSISTENT,
                List.of("a", "b"), "r", List.of("ev-y"), "policy-v1");
        ReportAssembler.AssembledReport report = ReportAssembler.assemble("snap-1",
                List.of(free));
        assertThat(report.excludedForeignSnapshotCount()).isZero();
        assertThat(report.hasConfirmedRootCause()).isTrue();
    }

    // ---------------- 确定性 ----------------

    @Test
    void 分节输出按键排序_输入顺序无关() {
        ReportAssembler.AssembledReport a = ReportAssembler.assemble("snap-1",
                List.of(corroboratedTrue("z"), corroboratedTrue("a"),
                        singleSourceTrue("m"), conflictUnknown("c")));
        ReportAssembler.AssembledReport b = ReportAssembler.assemble("snap-1",
                List.of(conflictUnknown("c"), singleSourceTrue("m"),
                        corroboratedTrue("a"), corroboratedTrue("z")));
        assertThat(a.confirmed()).extracting(ClaimVerdict::claimKey).containsExactly("a", "z");
        assertThat(a.speculative()).extracting(ClaimVerdict::claimKey).containsExactly("m");
        assertThat(a.unresolved()).extracting(ClaimVerdict::claimKey).containsExactly("c");
        assertThat(a.confirmed()).isEqualTo(b.confirmed());
        assertThat(a.speculative()).isEqualTo(b.speculative());
        assertThat(a.unresolved()).isEqualTo(b.unresolved());
    }
}

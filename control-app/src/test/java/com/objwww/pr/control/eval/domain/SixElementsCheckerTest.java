package com.objwww.pr.control.eval.domain;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六要素检出器单测（M-d T5 前置）：全绿面/缺把握面/缺证据面/文本兜底面。
 * 判分口径即纪律文档（.agent-notes/根因结论六要素纪律.md）的机器面。
 */
class SixElementsCheckerTest {

    private static ReportClaim claim(String type, List<String> refs) {
        return new ReportClaim(type, ClaimStatus.TRUE, "order-arena",
                "IDEMPOTENCY_BYPASS", List.of("ArenaDuplicateOrders"), refs);
    }

    private static EvidencePackageV2 fullPackage() {
        return new EvidencePackageV2(2,
                "订单服务在冻结窗内重复创单，同一意图被重复执行，重复订单计数升至 2",
                new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                List.of(claim("ROOT_CAUSE", List.of("ev-1", "ev-2"))),
                List.of("prometheus 曲线", "logs 重复 insert"),
                "影响：2 单重复，持续约 4 分钟，涉及 checkout 下单链路",
                "建议：对重复订单执行补偿废单，并复查幂等键写入路径（需 R2 审批）",
                List.of("artifact-1"));
    }

    @Test
    void completePackageWithConfidencePhraseIsAllGreen() {
        String text = "发生了什么：重复创单…… 根因：幂等失效 凭借指标与日志互证……把握：HIGH（多源一致且机理通顺）";
        SixElementsChecker.Result r = SixElementsChecker.check(fullPackage(), text);
        assertThat(r.complete()).isTrue();
        assertThat(r.confidenceLevel()).contains("HIGH");
    }

    @Test
    void missingConfidencePhraseFailsOnlyConfidenceElement() {
        SixElementsChecker.Result r = SixElementsChecker.check(fullPackage(), "无把握措辞的正文");
        assertThat(r.whatHappened()).isTrue();
        assertThat(r.rootCause()).isTrue();
        assertThat(r.evidenceBasis()).isTrue();
        assertThat(r.impact()).isTrue();
        assertThat(r.recommendation()).isTrue();
        assertThat(r.confidence()).isFalse();
        assertThat(r.complete()).isFalse();
        assertThat(r.confidenceLevel()).isEmpty();
    }

    @Test
    void nullReportTextFallsBackToSummaryAndImpactHaystack() {
        EvidencePackageV2 pkg = fullPackage();
        SixElementsChecker.Result r = SixElementsChecker.check(pkg, null);
        // 兜底文本=summary+impact（无把握短语）——结构五要素仍全绿
        assertThat(r.confidence()).isFalse();
        assertThat(r.rootCause()).isTrue();
        assertThat(r.evidenceBasis()).isTrue();

        EvidencePackageV2 withPhrase = new EvidencePackageV2(2,
                "重复创单 2 单。把握：MEDIUM（多源一致）",
                pkg.rootCause(), pkg.claims(), pkg.evidence(), pkg.impact(),
                pkg.remediation(), pkg.referenceArtifactRefs());
        SixElementsChecker.Result r2 = SixElementsChecker.check(withPhrase, null);
        assertThat(r2.confidence()).isTrue();
        assertThat(r2.confidenceLevel()).contains("MEDIUM");
    }

    @Test
    void claimsWithoutEvidenceRefsFailBasisElement() {
        EvidencePackageV2 pkg = new EvidencePackageV2(2,
                "摘要", new TypedRootCause("order-arena", "IDEMPOTENCY_BYPASS",
                        "DUPLICATE_CREATE_SAME_INTENT"),
                List.of(claim("ROOT_CAUSE", List.of())),   // 零引用=凭什么不成立
                List.of(), "影响", "建议", List.of());
        SixElementsChecker.Result r = SixElementsChecker.check(pkg, "把握：LOW（单源证据）");
        assertThat(r.evidenceBasis()).isFalse();
        assertThat(r.confidence()).isTrue();
        assertThat(r.confidenceLevel()).contains("LOW");
        assertThat(r.complete()).isFalse();
    }

    @Test
    void blankImpactFailsImpactElement() {
        EvidencePackageV2 pkg = new EvidencePackageV2(2,
                "摘要", new TypedRootCause("o", "f", "r"),
                List.of(claim("ROOT_CAUSE", List.of("ev-1"))),
                List.of(), "", "建议", List.of());
        SixElementsChecker.Result r = SixElementsChecker.check(pkg, "把握：HIGH（多源一致）");
        assertThat(r.impact()).isFalse();
        assertThat(r.complete()).isFalse();
    }
}

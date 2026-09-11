package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvalLogCompare 纯函数单测（EV-05）：统一形状抽取（含形状不符/截断标记）、
 * 错误行判定、签名归一化（时间戳/数字/UUID/hex 动态段）、分桶上限、diff 四桶。
 */
class EvalLogCompareTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractParsesUnifiedShapeAndTruncationFlag() {
        String payload = "{\"status\":\"success\",\"data\":{\"result\":["
                + "{\"ts\":\"2026-09-09T10:00:00Z\",\"service\":\"svc\",\"line\":\"ERROR a\"},"
                + "{\"service\":\"svc\",\"line\":\"no-ts line\"}"
                + "],\"truncated\":true}}";

        EvalLogCompare.Extraction out = EvalLogCompare.extract(mapper, payload);

        assertThat(out.shapeOk()).isTrue();
        assertThat(out.truncated()).isTrue();
        assertThat(out.lines()).hasSize(2);
        assertThat(out.lines().get(0).ts()).isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(out.lines().get(0).service()).isEqualTo("svc");
        assertThat(out.lines().get(1).ts()).isNull(); // 缺字段如实 null
    }

    @Test
    void extractRejectsMalformedOrForeignShapeHonestly() {
        assertThat(EvalLogCompare.extract(mapper, null).shapeOk()).isFalse();
        assertThat(EvalLogCompare.extract(mapper, "{oops").shapeOk()).isFalse();
        assertThat(EvalLogCompare.extract(mapper, "{\"data\":{\"result\":{}}}").shapeOk())
                .isFalse();
        assertThat(EvalLogCompare.extract(mapper, "{\"data\":{\"result\":[]}}").shapeOk())
                .isTrue();
    }

    @Test
    void errorLineJudgementIsCaseInsensitiveSubstring() {
        assertThat(EvalLogCompare.isErrorLine("2026 ERROR redis down")).isTrue();
        // 驼峰异常名必须命中（词边界会漏掉这类最常见形态）
        assertThat(EvalLogCompare.isErrorLine("java.lang.NullPointerException at x")).isTrue();
        assertThat(EvalLogCompare.isErrorLine("FATAL: OOM")).isTrue();
        assertThat(EvalLogCompare.isErrorLine("panic: runtime")).isTrue();
        assertThat(EvalLogCompare.isErrorLine("INFO all good")).isFalse();
        assertThat(EvalLogCompare.isErrorLine(null)).isFalse();
    }

    @Test
    void signatureNormalizesDynamicSegmentsAndTruncates() {
        String a = EvalLogCompare.signatureOf(
                "2026-09-09T10:00:01Z ERROR req 550e8400-e29b-41d4-a716-446655440000 "
                        + "took 1500ms code 0xdeadbeef");
        assertThat(a).isEqualTo("<ts> ERROR req <uuid> took <n>ms code <hex>");

        String long1 = EvalLogCompare.signatureOf("ERROR " + "x".repeat(300));
        assertThat(long1).hasSize(EvalLogCompare.MAX_SIGNATURE_CHARS);
    }

    @Test
    void bucketCountsOnlyErrorLinesAndCapsSignatures() {
        List<EvalLogCompare.LogLine> lines = List.of(
                new EvalLogCompare.LogLine(null, "s", "ERROR a 1"),
                new EvalLogCompare.LogLine(null, "s", "ERROR a 2"),
                new EvalLogCompare.LogLine(null, "s", "INFO fine"));

        Map<String, Long> buckets = EvalLogCompare.bucketBySignature(lines);

        assertThat(buckets).containsExactly(Map.entry("ERROR a <n>", 2L));
    }

    @Test
    void diffSplitsFourBucketsWithStableOrder() {
        Map<String, Long> baseline = new TreeMap<>();
        baseline.put("sig-same", 3L);
        baseline.put("sig-more", 5L);
        baseline.put("sig-less", 1L);
        baseline.put("sig-only-b", 2L);
        Map<String, Long> candidate = new TreeMap<>();
        candidate.put("sig-same", 3L);
        candidate.put("sig-more", 7L);
        candidate.put("sig-less", 4L);
        candidate.put("sig-only-c", 1L);

        EvalLogCompare.Diff diff = EvalLogCompare.diff(baseline, candidate, 10);

        assertThat(diff.onlyInBaseline()).containsExactly(
                new EvalLogCompare.SignatureCount("sig-only-b", 2L));
        assertThat(diff.onlyInCandidate()).containsExactly(
                new EvalLogCompare.SignatureCount("sig-only-c", 1L));
        // 候选计数升高 = increased（|delta| DESC 稳定序：sig-less Δ3 先于 sig-more Δ2）
        assertThat(diff.increased()).containsExactly(
                new EvalLogCompare.SignatureChange("sig-less", 1L, 4L),
                new EvalLogCompare.SignatureChange("sig-more", 5L, 7L));
        assertThat(diff.decreased()).isEmpty();
        assertThat(diff.unchanged()).isEqualTo(1);
    }

    @Test
    void diffCapsOutputBucketsAtTopN() {
        Map<String, Long> baseline = new TreeMap<>();
        for (int i = 0; i < 30; i++) {
            baseline.put(String.format("sig-%02d", i), 1L);
        }
        EvalLogCompare.Diff diff = EvalLogCompare.diff(baseline, Map.of(), 5);
        assertThat(diff.onlyInBaseline()).hasSize(5);
    }
}

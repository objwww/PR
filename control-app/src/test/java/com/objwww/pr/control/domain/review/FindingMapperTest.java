package com.objwww.pr.control.domain.review;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-05/UT-06：FindingMapper 行号工程映射与 fingerprint。
 */
class FindingMapperTest {

    private static final String HEAD = "abc123def456";
    private static final String FILE = "src/main/java/Foo.java";
    private static final String CONTENT = """
            package demo;

            public class Foo {
                public int div(int a, int b) {
                    return a / b;
                }
            }
            """;

    private final FindingMapper mapper = new FindingMapper();

    @Test
    void ut05_relocatesWrongModelLineNumberBySnippet() {
        // 模型报错位行号（99），但 existing_code 片段可定位 → 工程代码纠正为真实行号
        ModelFinding mf = new ModelFinding(FILE, 99, "return a / b;", "divide-by-zero", "MAJOR", "b 可能为 0");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings()).hasSize(1);
        ReviewFindingDraft draft = result.findings().get(0);
        assertThat(draft.lineStart()).isEqualTo(5); // 真实行号，不是模型报的 99
        assertThat(draft.lineEnd()).isEqualTo(5);
    }

    @Test
    void ut05_locatesMultiLineSnippetWithIndentDrift() {
        // 片段缩进与文件不一致（模型回显漂移）→ 行级 trim 匹配仍命中
        String snippet = "public int div(int a, int b) {\nreturn a / b;\n}";
        ModelFinding mf = new ModelFinding(FILE, 1, snippet, "npe-risk", "MINOR", "缺校验");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(4);
        assertThat(result.findings().get(0).lineEnd()).isEqualTo(6);
    }

    @Test
    void ut05_dropsAndCountsWhenSnippetNotFound() {
        ModelFinding mf = new ModelFinding(FILE, 5, "return a * b; // 不存在的片段", "rule", "MAJOR", "幻觉");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.findings()).isEmpty();
        assertThat(result.droppedCount()).isEqualTo(1);
    }

    @Test
    void ut05_dropsAndCountsWhenFileNotInSnapshot() {
        ModelFinding mf = new ModelFinding("src/Phantom.java", 1, "x", "rule", "MAJOR", "幻觉文件");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.findings()).isEmpty();
        assertThat(result.droppedCount()).isEqualTo(1);
    }

    @Test
    void ut06_sameContentSameFingerprint() {
        ModelFinding mf = new ModelFinding(FILE, null, "return a / b;", "divide-by-zero", "MAJOR", "b 可能为 0");

        var first = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf)).findings().get(0);
        var second = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf)).findings().get(0);

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
    }

    @Test
    void ut06_messageWhitespaceNormalized() {
        // message 空白差异（首尾空白/连续空格/内嵌换行）不影响 fingerprint
        ModelFinding a = new ModelFinding(FILE, null, "return a / b;", "rule", "MAJOR", "b 可能为 0");
        ModelFinding b = new ModelFinding(FILE, null, "return a / b;", "rule", "MAJOR", "  b\n可能为   0  ");

        var fa = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(a)).findings().get(0);
        var fb = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(b)).findings().get(0);

        assertThat(fa.fingerprint()).isEqualTo(fb.fingerprint());
    }

    @Test
    void fingerprintChangesWithHeadShaAndLineRange() {
        ModelFinding mf = new ModelFinding(FILE, null, "return a / b;", "rule", "MAJOR", "m");
        String otherContent = CONTENT.replace("return a / b;", "\nreturn a / b;"); // 行号移位

        var base = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf)).findings().get(0);
        var otherHead = mapper.map("fff999", Map.of(FILE, CONTENT), List.of(mf)).findings().get(0);
        var otherLine = mapper.map(HEAD, Map.of(FILE, otherContent), List.of(mf)).findings().get(0);

        assertThat(base.fingerprint()).isNotEqualTo(otherHead.fingerprint());
        assertThat(base.fingerprint()).isNotEqualTo(otherLine.fingerprint());
        assertThat(otherLine.lineStart()).isEqualTo(6);
    }

    // ---------------------------------------------------------- INC-19

    @Test
    void inc19_diffPlusPrefixedSnippetAnchors() {
        // 模型把 diff 的 '+' 行前缀抄进片段（真实 qwen-plus 联调暴露的形态）
        ModelFinding mf = new ModelFinding(FILE, 5, "+    return a / b;", "divide-by-zero", "MAJOR", "除零");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(5);
        assertThat(result.findings().get(0).lineEnd()).isEqualTo(5);
    }

    @Test
    void inc19_diffMinusPrefixedSnippetAnchorsWhenLineExistsElsewhere() {
        ModelFinding mf = new ModelFinding(FILE, 5, "-    return a / b;", "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(5);
    }

    @Test
    void inc19_multiLinePlusPrefixedSnippetAnchors() {
        String snippet = "+    public int div(int a, int b) {\n+        return a / b;\n+    }";
        ModelFinding mf = new ModelFinding(FILE, 1, snippet, "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(4);
        assertThat(result.findings().get(0).lineEnd()).isEqualTo(6);
    }

    @Test
    void inc19_uniformSpacePrefixedSnippetAnchors() {
        // diff 上下文行的统一前导单空格（剥离后应命中）
        String snippet = "     public int div(int a, int b) {\n         return a / b;";
        ModelFinding mf = new ModelFinding(FILE, 1, snippet, "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(4);
        assertThat(result.findings().get(0).lineEnd()).isEqualTo(5);
    }

    @Test
    void inc19_mixedDiffPrefixesRefused() {
        // +/- 混合前缀不是"统一抄来的同种前缀"，拒绝剥离 → 片段失配计 dropped
        String snippet = "+    return a / b;\n-    return a / b;";
        ModelFinding mf = new ModelFinding(FILE, 5, snippet, "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.findings()).isEmpty();
        assertThat(result.droppedCount()).isEqualTo(1);
    }

    @Test
    void inc19_normalIndentedCodeNotStripped() {
        // 回归保护：正常缩进的代码片段逐字存在于文件 → 第一级精确命中，剥离逻辑不参与
        String snippet = "    public int div(int a, int b) {\n        return a / b;";
        ModelFinding mf = new ModelFinding(FILE, 1, snippet, "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).lineStart()).isEqualTo(4);
        assertThat(result.findings().get(0).lineEnd()).isEqualTo(5);
    }

    @Test
    void inc19_diffStyleFilePathAnchors() {
        // diff 里的 a//b/ 前缀路径 → 归一化到快照规范路径，finding 落规范路径
        for (String variant : new String[]{"a/" + FILE, "b/" + FILE, "/" + FILE}) {
            ModelFinding mf = new ModelFinding(variant, 5, "return a / b;", "rule", "MAJOR", "m");

            FindingMapper.MappingResult result = mapper.map(HEAD, Map.of(FILE, CONTENT), List.of(mf));

            assertThat(result.droppedCount()).as("variant %s", variant).isZero();
            assertThat(result.findings().get(0).filePath()).isEqualTo(FILE);
            assertThat(result.findings().get(0).lineStart()).isEqualTo(5);
        }
    }

    @Test
    void inc19_exactPathMatchWinsOverPrefixStripping() {
        // 仓库里真实存在 "a/" 顶层目录时，精确命中优先，不误剥前缀
        String nested = "a/Foo.java";
        ModelFinding mf = new ModelFinding(nested, 1, "int x = 0/1;", "rule", "MAJOR", "m");

        FindingMapper.MappingResult result = mapper.map(HEAD,
                Map.of(nested, "int x = 0/1;\n"), List.of(mf));

        assertThat(result.droppedCount()).isZero();
        assertThat(result.findings().get(0).filePath()).isEqualTo(nested);
    }
}

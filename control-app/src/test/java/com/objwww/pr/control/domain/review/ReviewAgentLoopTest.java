package com.objwww.pr.control.domain.review;

import com.objwww.pr.control.domain.ai.MockModelClient;
import com.objwww.pr.control.domain.ai.ModelBudgetGuard;
import com.objwww.pr.control.domain.snapshot.SnapshotTree;
import com.objwww.pr.control.domain.tool.PolicyEngine;
import com.objwww.pr.control.domain.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ReviewAgentLoop：预算截断确定性 + 模型乱输出安全失败 + 正常一轮映射。
 */
class ReviewAgentLoopTest {

    private final MockModelClient model = new MockModelClient();
    private final ReviewAgentLoop loop = new ReviewAgentLoop(
            model, new ModelBudgetGuard(), new FindingMapper(), new PolicyEngine(new ToolRegistry()));

    private static SnapshotTree treeOf(String... pathContentPairs) {
        List<SnapshotTree.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < pathContentPairs.length; i += 2) {
            entries.add(new SnapshotTree.Entry(
                    pathContentPairs[i], pathContentPairs[i + 1].getBytes(StandardCharsets.UTF_8)));
        }
        return SnapshotTree.of(entries);
    }

    @Test
    void happyPath_mapsFindingsAndReportsStats() {
        SnapshotTree tree = treeOf("a/Foo.java", "class Foo {\n  int x = 0/1;\n}\n");
        model.enqueueContent("""
                ```json
                [{"file":"a/Foo.java","line":50,"existing_code":"int x = 0/1;","rule":"div-zero","severity":"MAJOR","message":"除零"}]
                ```
                """);

        ReviewOutcome outcome = loop.review(tree, "headsha1", "diff text", ReviewBudget.DEFAULT);

        assertThat(outcome.findings()).hasSize(1);
        assertThat(outcome.findings().get(0).lineStart()).isEqualTo(2); // 模型报 50，纠正为 2
        assertThat(outcome.droppedFindings()).isZero();
        assertThat(outcome.candidateFiles()).isEqualTo(1);
        assertThat(outcome.selectedFiles()).isEqualTo(1);
        assertThat(outcome.truncatedFiles()).isZero();
    }

    @Test
    void budgetTruncationIsDeterministicAndCounted() {
        // 6 个文件，maxFiles=3 → 字典序前 3 个入选，截断 3；跑两次结果一致
        SnapshotTree tree = treeOf(
                "f2.java", "2", "f1.java", "1", "f4.java", "4",
                "f3.java", "3", "f6.java", "6", "f5.java", "5");
        ReviewBudget budget = new ReviewBudget(3, 1L * 1024 * 1024, 1000, Duration.ofSeconds(10));
        model.enqueueContent("[]");
        model.enqueueContent("[]");

        ReviewOutcome first = loop.review(tree, "h", "d", budget);
        ReviewOutcome second = loop.review(tree, "h", "d", budget);

        assertThat(first.candidateFiles()).isEqualTo(6);
        assertThat(first.selectedFiles()).isEqualTo(3);
        assertThat(first.truncatedFiles()).isEqualTo(3);
        assertThat(first).isEqualTo(second);
        // prompt 中只含字典序前 3 个文件
        String prompt = model.requests().get(0).prompt();
        assertThat(prompt).contains("FILE: f1.java", "FILE: f2.java", "FILE: f3.java");
        assertThat(prompt).doesNotContain("FILE: f4.java");
    }

    @Test
    void byteBudgetTruncatesLargerRemainder() {
        // maxBytes=5：a(2B)+b(2B)=4B 入选，c(2B) 超限 → 截断 1
        SnapshotTree tree = treeOf("a", "12", "b", "34", "c", "56");
        ReviewBudget budget = new ReviewBudget(10, 5, 1000, Duration.ofSeconds(10));
        model.enqueueContent("[]");

        ReviewOutcome outcome = loop.review(tree, "h", "d", budget);

        assertThat(outcome.selectedFiles()).isEqualTo(2);
        assertThat(outcome.truncatedFiles()).isEqualTo(1);
    }

    @Test
    void malformedModelOutputFailsSafely() {
        SnapshotTree tree = treeOf("a/Foo.java", "class Foo {}\n");
        model.enqueueContent("我觉得这个 PR 写得挺好的，没什么问题。（非 JSON 输出）");

        assertThatThrownBy(() -> loop.review(tree, "h", "d", ReviewBudget.DEFAULT))
                .isInstanceOf(ModelOutputParseException.class);
    }

    @Test
    void nonArrayJsonFailsSafely() {
        SnapshotTree tree = treeOf("a/Foo.java", "class Foo {}\n");
        model.enqueueContent("{\"error\":\"not an array\"}");

        assertThatThrownBy(() -> loop.review(tree, "h", "d", ReviewBudget.DEFAULT))
                .isInstanceOf(ModelOutputParseException.class);
    }

    @Test
    void malformedEntrySkippedAndCounted() {
        SnapshotTree tree = treeOf("a/Foo.java", "class Foo {\n  int x = 0/1;\n}\n");
        // 一条缺 existing_code（畸形跳过计数），一条正常
        model.enqueueContent("""
                [{"file":"a/Foo.java","rule":"r","severity":"MAJOR","message":"没锚点"},
                 {"file":"a/Foo.java","existing_code":"int x = 0/1;","rule":"r2","severity":"MINOR","message":"ok"}]
                """);

        ReviewOutcome outcome = loop.review(tree, "h", "d", ReviewBudget.DEFAULT);

        assertThat(outcome.malformedFindings()).isEqualTo(1);
        assertThat(outcome.findings()).hasSize(1);
        assertThat(outcome.findings().get(0).ruleId()).isEqualTo("r2");
    }

    @Test
    void unlocatableSnippetDroppedAndCounted() {
        SnapshotTree tree = treeOf("a/Foo.java", "class Foo {}\n");
        model.enqueueContent("""
                [{"file":"a/Foo.java","existing_code":"不存在的代码片段","rule":"r","severity":"MAJOR","message":"幻觉"}]
                """);

        ReviewOutcome outcome = loop.review(tree, "h", "d", ReviewBudget.DEFAULT);

        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.droppedFindings()).isEqualTo(1);
    }

    @Test
    void inc19_findingInBudgetTruncatedFileStillAnchors() {
        // INC-19 第二轮真实回归：模型从 diff 全文抓到预算截断文件里的 bug 并逐字引用原文。
        // 映射面是全量快照（预算只约束 prompt 大小），该 finding 必须锚定而不是误丢。
        SnapshotTree tree = treeOf(
                "a/A.java", "class A {}\n",
                "b/B.java", "class B {}\n",
                "z/Service.java", "class S {\n  String sql = \"SELECT * FROM t WHERE u='\" + u + \"'\";\n}\n");
        ReviewBudget budget = new ReviewBudget(2, 1L * 1024 * 1024, 1000, Duration.ofSeconds(10));
        model.enqueueContent("""
                [{"file":"z/Service.java","line":2,"existing_code":"String sql = \\"SELECT * FROM t WHERE u='\\" + u + \\"'\\";","rule":"sql-injection","severity":"BLOCKER","message":"拼接 SQL"}]
                """);

        ReviewOutcome outcome = loop.review(tree, "h", "d", budget);

        assertThat(outcome.selectedFiles()).isEqualTo(2); // z/Service.java 未入选 prompt
        assertThat(outcome.truncatedFiles()).isEqualTo(1);
        assertThat(outcome.droppedFindings()).isZero(); // 但仍锚定成功
        assertThat(outcome.findings()).hasSize(1);
        assertThat(outcome.findings().get(0).filePath()).isEqualTo("z/Service.java");
        assertThat(outcome.findings().get(0).lineStart()).isEqualTo(2);
    }
}

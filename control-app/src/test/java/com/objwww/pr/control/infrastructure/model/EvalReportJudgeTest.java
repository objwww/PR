package com.objwww.pr.control.infrastructure.model;

import com.objwww.pr.control.eval.domain.repository.EvalReportJudge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P7 LLM-judge：fail-closed 未启用缺席、严格 JSON 解析、题序/题数/答案形态校验。 */
class EvalReportJudgeTest {

    private static final String REPORT = "{\"engine\":\"NATIVE\",\"analysis\":\"...\"}";

    private static EvalReportJudge.JudgeOutcome parse(String content) throws Exception {
        HttpEvalReportJudge judge = new HttpEvalReportJudge(
                "http://litellm-am3:4000", "key", "qwen3-max");
        Method m = HttpEvalReportJudge.class.getDeclaredMethod("parse", String.class);
        m.setAccessible(true);
        try {
            return (EvalReportJudge.JudgeOutcome) m.invoke(judge, content);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("fail-closed：base-url/api-key 缺席 → empty（未启用如实缺席，零外呼）")
    void unconfiguredReturnsEmpty() {
        assertThat(new HttpEvalReportJudge("", "key", null).judge(REPORT)).isEmpty();
        assertThat(new HttpEvalReportJudge("http://x", "", null).judge(REPORT)).isEmpty();
        assertThat(new HttpEvalReportJudge("http://x", "key", null).judge(" ")).isEmpty();
    }

    @Test
    @DisplayName("严格解析：全是=PASS 全题；含否=passed 计数；题序错位拒绝")
    void parseStrictAnswers() throws Exception {
        EvalReportJudge.JudgeOutcome allYes = parse(
                "{\"answers\":[{\"id\":\"Q1\",\"yes\":true},{\"id\":\"Q2\",\"yes\":true},{\"id\":\"Q3\",\"yes\":true}]}");
        assertThat(allYes.passed()).isEqualTo(3);
        assertThat(allYes.total()).isEqualTo(3);
        assertThat(allYes.rubricVersion()).isEqualTo("judge-rubric-v1");
        assertThat(allYes.model()).isEqualTo("qwen3-max");

        EvalReportJudge.JudgeOutcome partial = parse(
                "{\"answers\":[{\"id\":\"Q1\",\"yes\":true},{\"id\":\"Q2\",\"yes\":false},{\"id\":\"Q3\",\"yes\":true}]}");
        assertThat(partial.passed()).isEqualTo(2);

        assertThatThrownBy(() -> parse(
                "{\"answers\":[{\"id\":\"Q2\",\"yes\":true},{\"id\":\"Q1\",\"yes\":true},{\"id\":\"Q3\",\"yes\":true}]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("题序错位");

        assertThatThrownBy(() -> parse(
                "{\"answers\":[{\"id\":\"Q1\",\"yes\":true},{\"id\":\"Q2\",\"yes\":true}]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("题数不符");

        assertThatThrownBy(() -> parse(
                "{\"answers\":[{\"id\":\"Q1\",\"yes\":\"是\"},{\"id\":\"Q2\",\"yes\":true},{\"id\":\"Q3\",\"yes\":true}]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非布尔");

        assertThatThrownBy(() -> parse("抱歉我无法回答"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无 JSON");
    }

    @Test
    @DisplayName("围栏：带前后噪声的 JSON 仍可解析（模型输出宽容裁剪面）")
    void parsesWithSurroundingNoise() throws Exception {
        EvalReportJudge.JudgeOutcome outcome = parse(
                "好的，以下是结果：{\"answers\":[{\"id\":\"Q1\",\"yes\":false},{\"id\":\"Q2\",\"yes\":true},{\"id\":\"Q3\",\"yes\":false}]} 完毕");
        assertThat(outcome.passed()).isEqualTo(1);
    }
}

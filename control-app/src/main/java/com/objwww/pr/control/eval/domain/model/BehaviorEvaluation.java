package com.objwww.pr.control.eval.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * 逐案行为评测结果值对象（ME-T04/D04 第 5 条；纯数据，L0 零框架依赖）。
 *
 * <p>语义冻结：
 * <ul>
 *   <li>graderVersion：评分器版本——同案例不同 grader 版本并存落档，历史记录
 *       不被重评覆盖（V160 uq(case_result_id, grader_version)）；</li>
 *   <li>traceDigest：轨迹投影内容摘要（事件 ID/顺序 + 证据 digest 的确定性哈希），
 *       同轨迹同 grader 重算幂等校验面；读失败 ERROR 行如实 null；</li>
 *   <li>coverage：检查点覆盖双轨——text* = 旧版文本覆盖（报告文本子串命中，原名
 *       原义保留），evidence* = 新证据覆盖（实际 evidence/result 语料命中）；
 *       null = 无检查点未评；</li>
 *   <li>checks：每项带 status/reasonCode/证据引用（第 7 条）；metrics：每项带
 *       分子/分母；failureLabels：FAIL 检查的机器码标签；evidenceRefs：本案
 *       解析且同 run 归属的证据 ID 集。</li>
 * </ul>
 */
public record BehaviorEvaluation(String graderVersion,
                                 String traceDigest,
                                 Coverage coverage,
                                 List<Check> checks,
                                 List<Metric> metrics,
                                 List<String> failureLabels,
                                 List<String> evidenceRefs) {

    public BehaviorEvaluation {
        Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
        Objects.requireNonNull(coverage, "coverage 不得为 null");
        Objects.requireNonNull(checks, "checks 不得为 null");
        checks = List.copyOf(checks);
        Objects.requireNonNull(metrics, "metrics 不得为 null");
        metrics = List.copyOf(metrics);
        Objects.requireNonNull(failureLabels, "failureLabels 不得为 null");
        failureLabels = List.copyOf(failureLabels);
        Objects.requireNonNull(evidenceRefs, "evidenceRefs 不得为 null");
        evidenceRefs = List.copyOf(evidenceRefs);
    }

    /** 检查点覆盖双轨（null = 该轨未评；evidence 轨为 D04 新增，text 轨为旧版指标快照） */
    public record Coverage(Integer textCovered, Integer textTotal,
                           Integer evidenceCovered, Integer evidenceTotal) {
    }

    /** 单项检查：status 五态 + 机器码 reason + 证据引用位（证据 ID 文本） */
    public record Check(String name, BehaviorCheckStatus status, String reasonCode,
                        List<String> evidenceRefs) {

        public Check {
            Objects.requireNonNull(name, "name 不得为 null");
            Objects.requireNonNull(status, "status 不得为 null");
            Objects.requireNonNull(reasonCode, "reasonCode 不得为 null");
            Objects.requireNonNull(evidenceRefs, "evidenceRefs 不得为 null");
            evidenceRefs = List.copyOf(evidenceRefs);
        }
    }

    /** 比率指标分子/分母（分母 0 = 口径内无对象，如实不约分） */
    public record Metric(String name, long numerator, long denominator) {

        public Metric {
            Objects.requireNonNull(name, "name 不得为 null");
        }
    }
}

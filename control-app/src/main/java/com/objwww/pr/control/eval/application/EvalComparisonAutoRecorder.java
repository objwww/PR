package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.repository.EvalComparisonRepository;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * EV-07 终态自动落档（质量门缺档修复——eval_comparison 此前只认手工 POST，run 终态后
 * 无人落档）：eval run 终态化成功后（EvalBatchRunner.RunLifecycle 钩子）自动配对
 * 上一个终态 run 落档对比结论。
 *
 * <p>语义纪律：
 * <ul>
 *   <li>baseline = 同一 dataset_version + 同 panel（launch_plan 快照键）的最新终态
 *       run（{@link EvalQueryReader#findAutoCompareBaseline}）；无 baseline = 首跑，
 *       诚实不落档（qualityVerdict 恒 UNKNOWN 是正确语义，不编造对比对象）；</li>
 *   <li>幂等：本 run 已有 candidate 落档行（{@link EvalComparisonRepository
 *       #findLatestByCandidate}）即跳过——重放/重复终态事件不重复落档；</li>
 *   <li>落档内容 = EvalCompareService.record 同一计算路径的 insert-only 快照：
 *       无配对/簇不足/就绪度不足 → INCONCLUSIVE/NOT_EVALUABLE 照实落档
 *       （合法产出，非失败）；</li>
 *   <li>失败不拖垮 finalize：任何异常 catch 落 WARN，run 终态不受影响。</li>
 * </ul>
 */
public class EvalComparisonAutoRecorder {

    private static final Logger log = LoggerFactory.getLogger(EvalComparisonAutoRecorder.class);

    private final EvalQueryReader reader;
    private final EvalComparisonRepository comparisons;
    private final EvalCompareService compareService;
    private final String actor;

    public EvalComparisonAutoRecorder(EvalQueryReader reader,
                                      EvalComparisonRepository comparisons,
                                      EvalCompareService compareService,
                                      String actor) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.compareService = Objects.requireNonNull(compareService, "compareService");
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    /** run 终态钩子（仅 finalizeOnce 真实迁移成功后调用）；永不抛出 */
    public void onTerminal(UUID candidateRunId) {
        try {
            if (comparisons.findLatestByCandidate(candidateRunId).isPresent()) {
                return;
            }
            Optional<UUID> baseline = reader.findAutoCompareBaseline(candidateRunId);
            if (baseline.isEmpty()) {
                log.info("eval run {} 终态自动落档跳过：无同数据集/panel 的前序终态 run"
                        + "（首跑不落档）", candidateRunId);
                return;
            }
            compareService.record(baseline.get(), candidateRunId, actor)
                    .ifPresent(r -> log.warn("eval run {} 终态自动落档：baseline={} gate={}",
                            candidateRunId, baseline.get(), r.gate().outcome()));
        } catch (RuntimeException e) {
            log.warn("eval run {} 终态自动落档失败（不影响 run 终态）: {}",
                    candidateRunId, e.getMessage(), e);
        }
    }
}

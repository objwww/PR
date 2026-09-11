package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Run 配置代际历史端口（EN-04，V63 rca_run_config_epoch；增强线方案 §185/§207）：
 * {@code config_epoch → release_digest} 的<b>追加历史</b>——Run 初始 bundle 不可改写
 * 的前提下，热更新只追加新代际行，旧行与旧调用身份面（rca_model_call.config_epoch/
 * release_digest）永不覆盖。主键 (run_id, config_epoch) 即 §207 要求的
 * UNIQUE(run_id, config_epoch)：并发双切换的败者在 insert 上现形（ON CONFLICT 无行
 * 返回 → false → 应用事务整体回滚为 REJECTED_STALE，H05/H10）。
 *
 * <p>准入播种：insertRouted 携带 configDigest 的 run 同事务播种 epoch=0 行
 * （sourceCommandId=null、appliedBy='ADMISSION'）；EN-04 前铸造的存量 run 无史行，
 * 切换面 fail-closed 拒绝（无代际可校验，不猜 current）。
 *
 * <p>MIXED_CONFIG 判定（§235/H16）：history(runId) 行数 > 1 = 混合版本 Run，报告/
 * 评测归属面据此标记，禁止冒充纯单版本样本。实现方每方法自含短事务。
 */
public interface RunConfigEpochRepository {

    /**
     * 追加代际行；false = 该 (run_id, config_epoch) 已存在（幂等重放或并发胜者已落）。
     * 追加只增不改：既有行零改写。
     */
    boolean append(UUID runId, long configEpoch, String releaseDigest,
            UUID sourceCommandId, String appliedBy, String reason);

    /** 当前代际（epoch 最大行）；empty = 存量 run 无史（切换面 fail-closed） */
    Optional<EpochRow> findCurrent(UUID runId);

    /** 全量代际史（epoch 升序；H16 报告列 epoch/轮次的行源） */
    List<EpochRow> history(UUID runId);

    /** 代际行投影（追加事实：来源命令行可空 = 准入播种） */
    record EpochRow(UUID runId, long configEpoch, String releaseDigest,
            UUID sourceCommandId, String appliedBy, String reason, Instant createdAt) {
    }

    /**
     * 空史源桥（存量装配/测试面）：findCurrent 恒空、append 恒 false——绑定期
     * epoch 留白、准入不播种，EN-04 前行为零改动。生产装配必须注入 Postgres 实现。
     */
    RunConfigEpochRepository NO_OP = new RunConfigEpochRepository() {
        @Override
        public boolean append(UUID runId, long configEpoch, String releaseDigest,
                UUID sourceCommandId, String appliedBy, String reason) {
            return false;
        }

        @Override
        public Optional<EpochRow> findCurrent(UUID runId) {
            return Optional.empty();
        }

        @Override
        public List<EpochRow> history(UUID runId) {
            return List.of();
        }
    };
}

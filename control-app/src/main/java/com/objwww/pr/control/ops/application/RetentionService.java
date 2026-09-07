package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.model.LegalHold;
import com.objwww.pr.control.ops.domain.model.RetentionPolicy;
import com.objwww.pr.control.ops.domain.repository.PartitionCatalog;
import com.objwww.pr.control.ops.domain.repository.RetentionPolicyRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 保留策略服务（M5-18；INV-AM5-9：legal hold 阻断一切清理）。
 *
 * <p>归档候选判定（fail-closed 三段）：
 * <ol>
 *   <li>无生效策略 = 零候选（不归档比误删安全）；</li>
 *   <li>分区名不可解析出 yyyy_MM（含 default）= 永不候选（兜底分区承载边界外写入）；</li>
 *   <li>生效中 hold 覆盖族域（表名）或该分区（"表:分区"）= 剔除。</li>
 * </ol>
 * 过期锚 = 分区覆盖窗末（次月一日）+ hot retention 天数——过期前的分区绝不进候选。
 */
public class RetentionService {

    private final RetentionPolicyRepository policies;
    private final PartitionCatalog partitions;
    private final Supplier<Instant> clock;

    public RetentionService(RetentionPolicyRepository policies,
                            PartitionCatalog partitions,
                            Supplier<Instant> clock) {
        this.policies = policies;
        this.partitions = partitions;
        this.clock = clock;
    }

    /** INV-AM5-9：生效中 hold 覆盖该 scope（族名或"族:分区"）即阻断一切清理 */
    public boolean cleanupBlocked(String scope) {
        return policies.activeHolds().stream().anyMatch(h -> holdCovers(h, scope));
    }

    /** 归档候选（过期 + 未被 hold 阻断的月分区名，升序） */
    public List<String> archiveCandidates(String table) {
        Optional<RetentionPolicy> policy = policies.latestPolicy();
        if (policy.isEmpty()) {
            return List.of();
        }
        Instant now = clock.get();
        List<LegalHold> holds = policies.activeHolds();
        List<String> candidates = new java.util.ArrayList<>();
        for (String partition : partitions.partitionsOf(table)) {
            Optional<YearMonth> month = monthOf(table, partition);
            if (month.isEmpty()) {
                continue;   // default/不可解析分区永不候选
            }
            if (!expired(month.get(), policy.get().hotRetentionDays(), now)) {
                continue;
            }
            String partitionScope = table + ":" + partition;
            boolean held = holds.stream()
                    .anyMatch(h -> holdCovers(h, table) || holdCovers(h, partitionScope));
            if (!held) {
                candidates.add(partition);
            }
        }
        return List.copyOf(candidates);
    }

    /** 族域 hold（scope = 表名）覆盖表与其全部分区；分区域 hold（"表:分区"）精确覆盖 */
    static boolean holdCovers(LegalHold hold, String scope) {
        return hold.scope().equals(scope) || scope.startsWith(hold.scope() + ":");
    }

    /** 分区名尾缀 yyyy_MM → 月份；default/不可解析 = empty */
    static Optional<YearMonth> monthOf(String table, String partition) {
        String prefix = table + "_";
        if (!partition.startsWith(prefix) || partition.length() <= prefix.length()) {
            return Optional.empty();
        }
        String suffix = partition.substring(prefix.length());   // e.g. "2026_07"
        try {
            return Optional.of(YearMonth.parse(suffix.replace('_', '-')));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 分区覆盖窗末（次月一日零点 UTC）+ hot retention 后才算过期 */
    static boolean expired(YearMonth month, long hotRetentionDays, Instant now) {
        Instant windowEnd = month.plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return !now.isBefore(windowEnd.plus(Duration.ofDays(hotRetentionDays)));
    }
}

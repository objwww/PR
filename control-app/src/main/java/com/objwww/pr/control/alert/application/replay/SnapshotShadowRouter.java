package com.objwww.pr.control.alert.application.replay;

import com.objwww.pr.control.alert.domain.budget.RunBudget;

import java.util.Objects;

/**
 * Snapshot Shadow Router（AM4 M4-34）：Holmes/Native 读<b>同一冻结 Snapshot</b>
 * 的影子对照入口。两侧以各自真实代码执行（Baseline=Holmes Adapter 链，
 * Candidate=Native 固定链），路由器只做三件事：① 两侧 outcome 均盖章同一
 * snapshot_digest（同源对照面）；② 预算两侧独立（各自 RunBudget，Candidate 耗尽
 * 不动 Baseline）；③ <b>Candidate 失败捕获隔离</b>——异常转为显式失败结局（原因
 * 可见不吞），不外抛、不影响 Baseline 结局。<b>结构上无报告/发布出口</b>——影子
 * 结果不触生产结论（M4-38 G2 "不切主"的结构前提）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public final class SnapshotShadowRouter {

    /** 单侧影子执行：读给定快照、花给定侧预算、落库后返回 Claim 数 */
    public interface ShadowSide {

        int run(String snapshotDigest, RunBudget sideBudget);
    }

    /** 一侧结局：成功（Claim 数）或失败（原因可见，digest 仍盖章可对账） */
    public record SideOutcome(boolean succeeded, int claimCount, String snapshotDigest,
            String failReason) {
    }

    /** 影子对照结局：两侧各自独立，digest 三方一致（输入+两侧） */
    public record ShadowResult(SideOutcome baseline, SideOutcome candidate) {
    }

    /**
     * 执行一次影子对照：Baseline 先行且结局先定格，Candidate 后行——其任何
     * 运行时失败被捕获为显式结局（隔离的结构保证：后行侧不可能改写已定格结局）。
     */
    public ShadowResult compare(String snapshotDigest, ShadowSide baseline,
            ShadowSide candidate, RunBudget baselineBudget, RunBudget candidateBudget) {
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        SideOutcome baselineOutcome = runSafely(baseline, snapshotDigest, baselineBudget);
        SideOutcome candidateOutcome = runSafely(candidate, snapshotDigest, candidateBudget);
        return new ShadowResult(baselineOutcome, candidateOutcome);
    }

    private SideOutcome runSafely(ShadowSide side, String snapshotDigest, RunBudget budget) {
        try {
            return new SideOutcome(true, side.run(snapshotDigest, budget),
                    snapshotDigest, null);
        } catch (RuntimeException e) {
            return new SideOutcome(false, 0, snapshotDigest,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}

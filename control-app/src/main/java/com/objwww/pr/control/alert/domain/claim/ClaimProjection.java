package com.objwww.pr.control.alert.domain.claim;

import java.util.Objects;

/**
 * 断言投影写路径判定（AM4 M4-22 四分支，纯域函数——仓储只执行这里的判定）：
 * <ol>
 *   <li>fingerprint 不存在 → {@link Outcome#CREATED}（落新行）；</li>
 *   <li>fp 同 + hash 同 → {@link Outcome#UNCHANGED}（幂等重放，只追加审计事件）；</li>
 *   <li>fp 同 + hash 异 → {@link Outcome#REVISED}（CAS 更新当前投影，
 *       守卫 = priorHash：{@code UPDATE ... WHERE claim_hash = :priorHash}）；</li>
 *   <li>新 generation / 新 scope → 新 fingerprint（走 CREATED 落新行），
 *       旧记录由 {@link #shouldSupersede} 判定只标 SUPERSEDED——<b>不改内容</b>，
 *       历史不可变（证据失效 → 新观察+新快照+修订，历史报告不撤销反改）。</li>
 * </ol>
 * supersede 只取代<b>同 proposition</b>（claimKey+归一 scope+timeRange 相同）且
 * 严格更旧代际的投影：scope/时间窗不同是不同事实，永不互相取代；同代不同快照共存
 * （代际 ordering 原语与 V16 跨代栅栏一致）。
 */
public final class ClaimProjection {

    private ClaimProjection() {
    }

    /** 写分支结果 */
    public enum Outcome { CREATED, UNCHANGED, REVISED }

    /**
     * @param existingClaimHash 该 fingerprint 当前行内容哈希；null = 行不存在
     * @param verdictHash       待写 verdict 的 claim_hash
     */
    public static Decision decide(String existingClaimHash, String verdictHash) {
        Objects.requireNonNull(verdictHash, "verdictHash");
        if (existingClaimHash == null) {
            return new Decision(Outcome.CREATED, null);
        }
        if (existingClaimHash.equals(verdictHash)) {
            return new Decision(Outcome.UNCHANGED, existingClaimHash);
        }
        return new Decision(Outcome.REVISED, existingClaimHash);
    }

    /**
     * 旧投影是否被新 verdict 取代（只标 SUPERSEDED，不改内容）：
     * 同 proposition 且旧 generation 严格小于新 verdict 的 generation。
     */
    public static boolean shouldSupersede(ClaimStore.ClaimRow existing, ClaimVerdict newer) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(newer, "newer");
        return existing.observedGeneration() < newer.observedGeneration()
                && existing.claimKey().equals(newer.claimKey())
                && existing.scope().strip().equals(newer.scope().strip())
                && existing.timeRange().equals(newer.timeRange());
    }

    /** 判定结果；priorHash 同时是 REVISED 的 CAS 守卫（CREATED 时为 null） */
    public record Decision(Outcome outcome, String priorHash) {
        public Decision {
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}

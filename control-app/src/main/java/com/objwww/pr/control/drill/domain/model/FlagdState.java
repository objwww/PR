package com.objwww.pr.control.drill.domain.model;

/**
 * DR-05 flagd 当前状态快照（读面；方案 docs/告警-前端逐页体验改造与后期优化方案.md
 * §7.4 Flagd 段「记录修改前值和版本」的读取结果）：
 * <ul>
 *   <li>variant = 服务端实际 defaultVariant（读面原样，不做模板假定）；</li>
 *   <li>generation = 服务端代际令牌（flagd-admin 落码面 = 该 flag 条目 canonical JSON
 *       的 sha256——任何改写必变，同值重写也可检出）；null = 服务端不提供代际，
 *       条件恢复退化为纯值比对。</li>
 * </ul>
 */
public record FlagdState(String variant, String generation) {
}

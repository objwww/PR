package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 场景 → RCA Run 关联器（M3-17）：注入激活后等待告警链（AM1 intake → incident →
 * rca_run）铸出本场景的 run，并等其到达<b>终态</b>（SUCCEEDED/FAILED/CANCELLED/
 * SUPERSEDED——评分需要已完成的调查；M3-30 部署门实测修正：原版只等 run 出现，
 * Holmes 调查需数分钟，立即评分必然全落 TIMEOUT_OR_ABSENT）。
 *
 * <p>靶场场景优先走 arena.oa_scenario_map 回填行（M2-24 C-6，eval_app 只读，
 * 按 {@link ArenaChaosScenarioDriver#effectiveScenarioId} 的每轮独立 id 匹配），
 * 回填行缺失时退到 incident_key 匹配；flagd 场景走 incident_key 的 alertname
 * 匹配（激活时刻之后新铸）。超时未出现 = empty（评分落 TIMEOUT_OR_ABSENT，
 * 不猜不凑）。
 */
public interface RcaRunResolver {

    Optional<UUID> resolve(GoldenCase golden, int roundNo, Instant activatedAt,
                           int timeoutSeconds);
}

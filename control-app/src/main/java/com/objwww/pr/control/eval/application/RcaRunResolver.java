package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 场景 → RCA Run 关联器（M3-17）：注入激活后等待告警链（AM1 intake → incident →
 * rca_run）铸出本场景的 run。靶场场景走 arena.oa_scenario_map 回填行（M2-24 C-6，
 * eval_app 只读）；flagd 场景走 incident_key 的 alertname 匹配（激活时刻之后新铸）。
 * 超时未出现 = empty（评分落 TIMEOUT_OR_ABSENT，不猜不凑）。
 */
public interface RcaRunResolver {

    Optional<UUID> resolve(GoldenCase golden, Instant activatedAt, int timeoutSeconds);
}

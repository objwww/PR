package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.eval.application.ArenaChaosScenarioDriver;
import com.objwww.pr.control.eval.application.ChaosAdminClient;
import com.objwww.pr.control.eval.domain.GoldenCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;
import java.util.Objects;

/**
 * DR-04 arena-chaos 恢复适配（{@link ArenaChaosDrillInjection} 的对侧；注入面 = POST
 * /chaos/{faultType}/on，恢复面 = 同契约的 off CAS + status 读面）：固定身份纪律与
 * 注入对称——有效实例 id 由作业 id 派生（chaos-eval-d{hex}-{sid}-r1，与注入同式，
 * 不换 id 盲重试），恢复动作凭 status 读回的实时代际做 CAS。
 *
 * <p>三态（chaos-admin 会话状态机 PREPARED/ACTIVE/RECOVERING/CLOSED）：
 * <ul>
 *   <li>ACTIVE → off CAS（scenarioId + expectedGeneration）：受理 = RECOVERED
 *       （故障已关，会话收口归 chaos-admin reaper，症状清除归 VERIFYING 核验）；
 *       409 代际竞争 = UNKNOWN 下拍重试；</li>
 *   <li>RECOVERING/CLOSED → RECOVERED（off 已被本作业前拍或 TTL reaper 受理/
 *       恢复收口完成——恢复方向幂等，不重复 off）；</li>
 *   <li>404 未知场景 → RECOVERED（管理面确认无此会话 = 注入未生效，无可恢复面，
 *       如实记录不假装执行了恢复动作）；</li>
 *   <li>传输失败/未知状态 → UNKNOWN 下拍重试（上限归 worker 恢复窗口截止）。</li>
 * </ul>
 */
public final class ArenaChaosDrillRecovery {

    private static final Logger log = LoggerFactory.getLogger(ArenaChaosDrillRecovery.class);

    private final ChaosAdminClient client;

    public ArenaChaosDrillRecovery(ChaosAdminClient client) {
        this.client = Objects.requireNonNull(client);
    }

    DrillRecoveryPort.RecoverOutcome recover(DrillJob job, DrillTemplate template,
                                             GoldenCase golden) {
        if (golden.chaosFamily() == null || golden.chaosFamily().isBlank()) {
            return DrillRecoveryPort.RecoverOutcome.failed(
                    "靶场场景缺 chaos_family（配置缺陷，确定不可恢复）: "
                            + golden.scenarioId());
        }
        // 对账锚与注入同式派生（DU07 固定身份纪律：恢复面凭同一 id 查会话）
        String sid = ArenaChaosScenarioDriver.effectiveScenarioId(golden, 1,
                ArenaChaosDrillInjection.runTagFor(job));
        ChaosAdminClient.SessionStatus status;
        try {
            status = client.status(sid);
        } catch (HttpClientErrorException.NotFound e) {
            return DrillRecoveryPort.RecoverOutcome.recovered(
                    "chaos_session_absent: 管理面无此会话（" + sid
                            + "）——注入未生效，无可恢复面");
        } catch (RuntimeException e) {
            return DrillRecoveryPort.RecoverOutcome.unknown(
                    "chaos_status_unreadable: " + sid + " — "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return switch (status.state()) {
            case "CLOSED" -> DrillRecoveryPort.RecoverOutcome.recovered(
                    "chaos_session_closed: " + sid + "（恢复已收口）");
            case "RECOVERING" -> DrillRecoveryPort.RecoverOutcome.recovered(
                    "chaos_off_accepted: " + sid
                            + "（off 已受理/TTL 收口进行中，故障已关；会话收口归 chaos-admin reaper）");
            case "ACTIVE" -> {
                boolean accepted;
                try {
                    accepted = client.deactivate(golden.chaosFamily(), Map.of(
                            "scenarioId", sid,
                            "expectedGeneration", status.generation()));
                } catch (RuntimeException e) {
                    yield DrillRecoveryPort.RecoverOutcome.unknown(
                            "chaos_off_unknown: " + sid + " — "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                if (!accepted) {
                    log.warn("drill {} chaos off CAS 未中（代际竞争，下拍重试）: {}",
                            job.id(), sid);
                    yield DrillRecoveryPort.RecoverOutcome.unknown(
                            "chaos_cas_rejected: " + sid + "（代际竞争，下拍按实时代际重试）");
                }
                yield DrillRecoveryPort.RecoverOutcome.recovered(
                        "chaos_off_accepted: " + sid + "（off CAS 受理）");
            }
            default -> DrillRecoveryPort.RecoverOutcome.unknown(
                    "chaos_session_state_unexpected: " + sid + " state=" + status.state());
        };
    }
}

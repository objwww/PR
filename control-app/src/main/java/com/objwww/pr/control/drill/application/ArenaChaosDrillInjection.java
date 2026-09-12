package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import com.objwww.pr.control.eval.application.AlertProbe;
import com.objwww.pr.control.eval.application.ArenaChaosScenarioDriver;
import com.objwww.pr.control.eval.application.ArenaTrafficClient;
import com.objwww.pr.control.eval.application.ChaosAdminClient;
import com.objwww.pr.control.eval.application.ScenarioDriver;
import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.Objects;

/**
 * DR-03 arena-chaos 注入适配（§7.5 DR-03 + §7.4 固定身份纪律）：复用
 * {@link ArenaChaosScenarioDriver}（W1 已修三债：中断即退零流量、回执先行成形、
 * 恢复探针查有效实例 id）的完整 recipe——on 激活 → 开关读面 settle → 按故障族
 * 注入 chaos 前缀评测流量。
 *
 * <p>固定身份（DU07「on 已成功但响应丢失：不换 ID 重复激活」）：runTag 由
 * drill job id 派生（"d" + UUID hex），每作业有效实例 id 恒定
 * （chaos-eval-d{hex}-{sid}-r1）——ACTION_UNKNOWN 对账与 DR-04 恢复面凭同一 id
 * 查会话，不生成新 id 盲重试。为此每作业构造独立 driver 实例（runTag 是构造期
 * 注入的批次身份；共享 eval 批次的 driver 会把多场演练并入同一 uq_chaos_scenario
 * 身份，跨作业撞唯一约束/串场）。
 *
 * <p>三态：activate 返回回执 → PERFORMED（回执 JSON 见
 * {@link DrillInjectionReceipt}）；{@link ArenaChaosScenarioDriver.ActivationException}
 * = 会话已激活（回执身份随异常移交），故障注入确定发生 → PERFORMED 且
 * trafficNote 如实记录流量/等待阶段失败；其余异常经
 * {@link DrillInjectionFailures} 三态化。
 */
public final class ArenaChaosDrillInjection {

    private final ChaosAdminClient client;
    private final AlertProbe alertProbe;
    private final ArenaTrafficClient traffic;
    private final String datasetVersion;

    public ArenaChaosDrillInjection(ChaosAdminClient client, AlertProbe alertProbe,
                                    ArenaTrafficClient traffic, String datasetVersion) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.traffic = Objects.requireNonNull(traffic);
        this.datasetVersion = Objects.requireNonNull(datasetVersion);
    }

    /** 作业固定身份：runTag = "d" + drill id hex（[a-z0-9] 合规），同作业恒定 */
    static String runTagFor(DrillJob job) {
        return "d" + job.id().toString().replace("-", "");
    }

    DrillInjectionPort.Outcome inject(DrillJob job, DrillTemplate template,
                                      GoldenCase golden) {
        String runTag = runTagFor(job);
        // 对账锚在调用前成形：UNKNOWN 时回执未得，reason 凭此固定 id 查会话
        String fixedId = ArenaChaosScenarioDriver.effectiveScenarioId(golden, 1, runTag);
        ArenaChaosScenarioDriver driver = new ArenaChaosScenarioDriver(
                client, alertProbe, traffic, datasetVersion, runTag);
        try {
            ScenarioDriver.ActivationReceipt receipt = driver.activate(golden, 1);
            return DrillInjectionPort.Outcome.performed(
                    DrillInjectionReceipt.json(job, "ArenaChaosScenarioDriver",
                            receipt, null));
        } catch (ArenaChaosScenarioDriver.ActivationException e) {
            // W1 中断/流量失败路径：会话已在管理面激活（回执身份随异常移交）——
            // 故障注入确定发生（PERFORMED），流量/等待阶段失败如实入回执
            return DrillInjectionPort.Outcome.performed(
                    DrillInjectionReceipt.json(job, "ArenaChaosScenarioDriver",
                            e.receipt(), e.getMessage()));
        } catch (RuntimeException e) {
            return DrillInjectionFailures.from(e, fixedId);
        }
    }
}

package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * DR-02 服务端预检（§7.2"开始前的检查必须在服务端重新执行"；§7.4 预检六项）：
 * 纯函数装配检查结果——真实可查信号出 OK/FAIL，control 面查不了的项如实 UNKNOWN
 * 并给原因，不造假（任务红线）。
 *
 * <p>信号面盘点（control_app 身份可达性）：
 * <ul>
 *   <li>SCENARIO_KNOWN / TARGET_ENV_WHITELIST / ENV_OCCUPANCY / EXECUTION_READY：
 *       本作业链自持有（目录/部署白名单/drill_job 占位/模板可执行标记）→ 真值；</li>
 *   <li>TARGET_HEALTH（靶场健康）/ RESOURCE_HEADROOM（资源水位）/ RESIDUAL_FAULT
 *       （旧故障残留，chaos_session 在 chaos_admin 域 control_app 零授权）/
 *       MGMT_PLANE（管理面可达）/ RECOVERY_CAPABILITY（恢复能力）：control 读面
 *       无真实信号 → UNKNOWN（诚实边界，待 DR-03/04 执行域接线后改由 worker 侧
 *       实测回填）。</li>
 * </ul>
 * canLaunch = 无 FAIL（UNKNOWN 不阻塞但逐条可见；EXECUTION_READY 是已知能力面，
 * 未交付 = FAIL 不冒充 UNKNOWN——不开放假启动）。
 */
public final class DrillPrecheck {

    private DrillPrecheck() {
    }

    public enum Status {OK, FAIL, UNKNOWN}

    public record Check(String name, Status status, String detail) {
    }

    public record Result(List<Check> checks) {

        public Result {
            checks = List.copyOf(checks);
        }

        public boolean canLaunch() {
            return checks.stream().noneMatch(c -> c.status() == Status.FAIL);
        }
    }

    /**
     * @param template  场景模板（null = 未知场景，SCENARIO_KNOWN FAIL 后短路）
     * @param targetEnv 请求靶场
     * @param allowedEnvs 部署靶场白名单
     * @param occupant  同靶场活动作业（无 = null；ENV_OCCUPANCY 真值源）
     */
    public static Result run(DrillTemplate template, String targetEnv,
                             List<String> allowedEnvs, DrillJob occupant) {
        List<Check> checks = new ArrayList<>();
        if (template == null) {
            checks.add(new Check("SCENARIO_KNOWN", Status.FAIL, "场景未注册于模板目录"));
            return new Result(checks);
        }
        checks.add(new Check("SCENARIO_KNOWN", Status.OK,
                "模板目录已注册：" + template.scenarioId()));
        checks.add(new Check("TARGET_ENV_WHITELIST",
                allowedEnvs.contains(targetEnv) ? Status.OK : Status.FAIL,
                allowedEnvs.contains(targetEnv)
                        ? "靶场在部署白名单内" : "靶场不在部署白名单内（篡改面拒绝，DU04）"));
        checks.add(new Check("ENV_OCCUPANCY",
                occupant == null ? Status.OK : Status.FAIL,
                occupant == null
                        ? "同靶场无活动演练（数据库原子占位可用）"
                        : "同靶场已被作业 " + occupant.id() + " 占用（state="
                          + occupant.state() + "）——§7.3 互斥，恢复异常占位同样阻止（DU15）"));
        checks.add(new Check("EXECUTION_READY",
                template.execution().ready() ? Status.OK : Status.FAIL,
                template.execution().ready()
                        ? "注入执行面已交付" : template.execution().reason()));
        // ---- control 读面无真实信号的项：如实 UNKNOWN，不造假 ----
        checks.add(new Check("TARGET_HEALTH", Status.UNKNOWN,
                "靶场健康需管理/观测面信号，control 读面无授权通路（待 DR-03 执行域实测）"));
        checks.add(new Check("RESOURCE_HEADROOM", Status.UNKNOWN,
                "资源水位需观测面信号，control 读面无授权通路（待 DR-03 执行域实测）"));
        checks.add(new Check("RESIDUAL_FAULT", Status.UNKNOWN,
                "旧故障残留以 chaos_session 为准（chaos_admin 域，control_app 零授权）；"
                        + "本可查的演练占位已单列为 ENV_OCCUPANCY"));
        checks.add(new Check("MGMT_PLANE", Status.UNKNOWN,
                "管理面可达性仅在 worker 执行域可测，control 读面不直连管理面（§7.3）"));
        checks.add(new Check("RECOVERY_CAPABILITY", Status.UNKNOWN,
                "恢复能力以执行域恢复通路为准（DR-04/DR-05 未交付时不冒充可恢复）"));
        return new Result(checks);
    }
}

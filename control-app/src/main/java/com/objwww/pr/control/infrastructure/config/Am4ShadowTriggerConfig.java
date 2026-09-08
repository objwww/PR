package com.objwww.pr.control.infrastructure.config;

import com.objwww.pr.control.alert.application.Am4ShadowTrigger;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.release.application.EngineComparisonRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

/**
 * AM4 影子触发一次性入口装配（195 部署配方 §5 方式 A 的 runner 挂载点，
 * E2E 执行者工具——非生产触发器）：
 *
 * <pre>
 * docker compose run --rm --no-deps control-app \
 *   --spring.profiles.active=docker,am4-shadow-trigger \
 *   --spring.main.web-application-type=none \
 *   --am4.shadow-trigger.holmes-run-id=&lt;holmes run uuid&gt;
 * </pre>
 *
 * <p>须与 {@code docker} profile 同开（依赖 AlertAm4Config 装配的三 Agent/
 * Supervisor）；web-application-type=none 保证一次性实例不接入 webhook 面。
 * 正式触发入口形态仍是 G2 终裁开放项（配方 §6.1），本装配不发明。
 * 引擎对照记录器装配已上收 AlertFlowConfig 公共面（BA-56：M6-05 反向影子
 * worker 复用后，docker profile 常驻进程同需此依赖，不能挂本 profile）。
 *
 * @author wanghua
 * @date 2026-09-05
 */
@Configuration
@Profile("am4-shadow-trigger")
public class Am4ShadowTriggerConfig {

    @Bean
    public CommandLineRunner am4ShadowTrigger(DeterministicSupervisor am4DeterministicSupervisor,
            RcaRunRepository rcaRunRepository, RcaTaskRepository rcaTaskRepository,
            EvidenceRepository evidenceRepository,
            EvidenceSnapshotRepository evidenceSnapshotRepository,
            MetricsAgent am4MetricsAgent, LogsAgent am4LogsAgent, ChangeAgent am4ChangeAgent,
            NativeRcaAgent am4NativeRcaAgent, SchedulerSlotRepository schedulerSlotRepository,
            EngineComparisonRecorder engineComparisonRecorder,
            @Value("${app.alert.worker.slot-scope:rca}") String slotScope,
            @Value("${am4.shadow-trigger.holmes-run-id}") UUID holmesRunId) {
        Am4ShadowTrigger trigger = new Am4ShadowTrigger(am4DeterministicSupervisor,
                rcaRunRepository, rcaTaskRepository, evidenceRepository,
                evidenceSnapshotRepository, am4MetricsAgent, am4LogsAgent, am4ChangeAgent,
                am4NativeRcaAgent, schedulerSlotRepository, slotScope, AlertClock.system(),
                engineComparisonRecorder);
        return args -> {
            trigger.trigger(holmesRunId);
            // 影子池非 daemon 线程会挂住 JVM——一次性入口显式退出（Spring shutdown
            // hook 仍会执行，am4ShadowPool 的 destroyMethod 正常回收）
            System.exit(0);
        };
    }
}

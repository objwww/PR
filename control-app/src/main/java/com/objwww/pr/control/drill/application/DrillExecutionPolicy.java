package com.objwww.pr.control.drill.application;

import com.objwww.pr.shared.Digest;

import java.util.List;

/**
 * FUP-01 演练执行政策（launch 能力位的单一事实源）：{@link DrillJobService}
 * （API preview/create）、{@link DrillWorker}（领取后复验）与
 * {@link CompositeDrillInjection}（最靠近副作用的最终边界）共用同一对象，
 * 杜绝"入口已关、执行面仍开"的口径漂移。
 *
 * <p>加载语义（如实标记）：配置（{@code app.drill.launch-enabled} /
 * {@code app.drill.target-envs}）仅在进程启动时装配，<b>非热更新</b>——翻转能力位
 * 需重启生效，本政策不宣称即时急停。API 与 worker 分进程部署时，各自从同名配置
 * 构造本对象，{@link #policyFingerprint()} 供两侧核对同源（部署验收对账锚）。
 */
public final class DrillExecutionPolicy {

    /** 政策规则版本（拒绝语义变更时升版；能力响应携带便于跨进程核对） */
    public static final String POLICY_VERSION = "drill-exec-policy-v1";

    /** 关闭期统一拒绝原因码（与 create 的 409 同码，SAFE-04/FUP-01/FUP-04） */
    public static final String REASON_CODE = "LAUNCH_DISABLED";

    private final boolean launchEnabled;
    private final String fingerprint;

    public DrillExecutionPolicy(boolean launchEnabled, List<String> allowedEnvs) {
        this.launchEnabled = launchEnabled;
        this.fingerprint = Digest.sha256Of(POLICY_VERSION
                + "|launchEnabled=" + launchEnabled
                + "|allowedEnvs=" + allowedEnvs.stream().sorted().toList()).value();
    }

    public boolean launchEnabled() {
        return launchEnabled;
    }

    public String policyVersion() {
        return POLICY_VERSION;
    }

    /** 配置指纹（policyVersion + 能力位 + 靶场白名单 的 canonical 摘要） */
    public String policyFingerprint() {
        return fingerprint;
    }

    /** 关闭期拒绝文案（worker 终态卡因 / 预检 FAIL detail / 注入边界卡因 同源） */
    public String disabledReason() {
        return REASON_CODE + ": 演练启动面已关闭（app.drill.launch-enabled=false，"
                + "SAFE-04/FUP-01）——DR-03/04 停止/恢复推进链已交付，重开需改配置并"
                + "重启生效（启动时加载，非即时急停），policyVersion=" + POLICY_VERSION;
    }
}

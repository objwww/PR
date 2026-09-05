package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;

import java.util.List;

/**
 * 统一场景驱动器（M3-17 冻结契约，评审 P0-4/5）：注入与恢复的唯一出口，
 * 统一返回结构化回执——eval-runner 不感知具体注入机制（flagd/靶场 chaos/基础设施）。
 *
 * <p>实现三件（首批）：{@code FlagdScenarioDriver}（S1/S2，flagd defaultVariant 精确
 * 切换，BA-19 单键纪律）、{@code ArenaChaosScenarioDriver}（S3~S5，eval-mgmt 私网
 * 调 ChaosController，CHAOS_ADMIN_TOKEN 仅 env 注入 INV-AM3-3）、
 * {@code InfrastructureScenarioDriver}（预留，首批 5 场景未用）。
 *
 * <p>S1/S2 恢复判定<b>不复用</b> AM2 的 {@code *_current==0}——各自按注册表
 * recovery.criteria 定义（flagd 归位 + 告警 resolved + 指标回落），统一收敛为
 * {@link RecoveryReceipt} 的 criteriaMet/alertsResolved 两布尔。
 */
public interface ScenarioDriver {

    /** 注入激活（预热等待归 runner 编排，不在 driver 内 sleep 固定值） */
    ActivationReceipt activate(GoldenCase golden);

    /** 注入解除 + 恢复确认（实现须执行恢复探针；解除失败同样返回回执不抛半途） */
    RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt);

    /** 激活回执（M3-17 冻结字段：场景/动作摘要/代数/期望告警身份） */
    record ActivationReceipt(String scenarioId,
                             String actionDigest,
                             long generation,
                             String expectedAlertIdentity) {
    }

    /** 恢复回执：criteriaMet = 注入面恢复条件满足；alertsResolved = 告警无残留 firing */
    record RecoveryReceipt(String scenarioId,
                           String actionDigest,
                           long generation,
                           boolean criteriaMet,
                           boolean alertsResolved,
                           List<String> unmetCriteria) {
    }
}

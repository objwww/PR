package com.objwww.pr.control.eval.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 批件有效 run-tag 派生（BA-190：2026-09-19 批件 efde9e17/44f220ef 空 tag 撞
 * uq_chaos_scenario 全灭假绿定谳）。run-tag 由启动方 env 注入（逐批唯一），但未注入
 * 的启动路径会让靶场场景 id 退化为 chaos-eval-{sid}-r{round}，撞上永存台账的
 * 全局唯一索引——空 tag 必须兜底为逐批唯一值。
 *
 * <p>派生纪律：配置的 tag 非空且剔除后仍有合法字符 = 原样使用（旧语义零漂移）；
 * 空 tag——或剔除后为空（如全下划线/全中文值，{@code effectiveScenarioId} 的
 * [^a-z0-9-] 剔除会把它剥成空串，兜底被绕过）——按 evalRunId 派生
 * {@code "r" + uuid 前 12 位 hex}（BA-191 由 8 位加宽——降低跨批理论碰撞面，
 * 字符集不变）——hex 天然落在 scenario id 合法字符集
 * [a-z0-9] 内（{@link ArenaChaosScenarioDriver#effectiveScenarioId} 的剔字规则
 * 不改变它），且同一 evalRunId 幂等（崩溃重放稳定身份 → 同 tag，重跑批次 =
 * 新 evalRunId → 新 tag）。runner 注入面与 RcaRunResolver 匹配面必须共用本函数
 * 的同一结果，否则 run 匹配断裂。
 */
public final class EvalRunTags {

    private EvalRunTags() {
    }

    /**
     * 本批有效 run-tag：配置非空且剔除后仍有合法字符 = 配置值；空或剔除后为空 =
     * "r" + evalRunId 前 12 位 hex（剔除规则与 {@link ArenaChaosScenarioDriver#effectiveScenarioId}
     * 同律——注入面与解析面共用本函数结果，剔除判空必须同源，否则 tag 全非法字符时
     * 注入侧剥成空串撞 chaos-eval-{sid}-r{round}，兜底形同虚设）
     */
    public static String effective(String configuredRunTag, UUID evalRunId) {
        if (configuredRunTag != null && !configuredRunTag.isBlank()
                && !sanitize(configuredRunTag).isEmpty()) {
            return configuredRunTag;
        }
        Objects.requireNonNull(evalRunId, "空 run-tag 兜底派生需要 evalRunId");
        return "r" + evalRunId.toString().replace("-", "").substring(0, 12);
    }

    /** 与 effectiveScenarioId 同律剔字：转小写后剔出 [a-z0-9-] 之外字符 */
    private static String sanitize(String runTag) {
        return runTag.toLowerCase().replaceAll("[^a-z0-9-]", "");
    }
}

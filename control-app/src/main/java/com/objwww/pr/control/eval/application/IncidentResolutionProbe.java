package com.objwww.pr.control.eval.application;

/**
 * incident 面 resolved 探针（2026-09-09 S3 smoke 多轮结构冲突修复）：Prometheus 告警
 * resolved ≠ 告警链已消费 resolved——同指纹告警在 Alertmanager resolve_timeout 内再
 * firing 会被并进未关闭的 episode（repeat_interval=4h 抑制重投，R2 拿不到 webhook），
 * 且 incident 未落 RESOLVED 时同材料 firing 不铸新 run（IncidentProjector 合并律）。
 * 因此下一轮注入前必须确认 incident 已落 RESOLVED（= AM 已投递并消费 resolved
 * webhook，episode 真正关闭）。默认实现查 Postgres incident 表（eval_app V11 只读
 * 授权面），真栈行为归 195 门；测试以假件替换。超时返回 false 不抛中断批量。
 */
public interface IncidentResolutionProbe {

    /** 在 maxWaitSeconds 内 alertname 对应最新 incident 落 RESOLVED（无行 = 无残留）？ */
    boolean awaitIncidentResolved(String alertname, int maxWaitSeconds);
}

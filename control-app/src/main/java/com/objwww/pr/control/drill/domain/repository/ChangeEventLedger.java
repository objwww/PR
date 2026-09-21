package com.objwww.pr.control.drill.domain.repository;

import java.time.Instant;

/**
 * BA-185 变更事实台账（V40 change_event 写入窄面）：评测/演练注入即"发布"时，
 * 发布事实如实落账——flagd 翻转自身零账本行（195 实证：change_event 全表仅
 * control-app 的 config_activation/deployment 行，payment 服务零行），注入侧
 * 不补写 = change.query 查无此发布 = 故障与账本假联动。
 *
 * <p>纪律与 V40 同律：append-only；幂等锚 (source, deploy_id) 重放零重复；写失败
 * = "生效而无证据"，由调用方判败（fail-closed，不静默降级为无账本调查）。
 * ROLLBACK 行纪律：dry_run 未真实执行回滚时不虚构账本（BA-185）；BA-191 起
 * 真执行链（mutation_unlock_registry 放行的 service.rollback operation 真实写回
 * flagd 旗标）由执行器在生效后如实落 ROLLBACK 行——drill/driver 恢复面自身仍不写
 * 反向行（恢复是收场，不是一次新发布/回滚的处置事实）。
 */
public interface ChangeEventLedger {

    /** 发布生效事实落账（source=deployment / action=DEPLOY / status=SUCCEEDED） */
    void recordDeploy(DeployFact fact);

    /**
     * BA-191 回滚生效事实落账（source=deployment / action=ROLLBACK /
     * rollback_of=被回滚发布的 config_digest）——仅真执行链调用；BA-191 前的
     * 台账假件未实现本面，触达即装配缺陷，显式炸出不静默丢证据
     */
    default void recordRollback(RollbackFact fact) {
        throw new UnsupportedOperationException("本台账面未实现回滚落账（BA-191 真执行链专用）");
    }

    /** 发布事实（change_event 行的域投影；configDigest=发布内容指纹 char(64)） */
    record DeployFact(String deployId, String service, String environment,
                      String configDigest, String actor, Instant effectiveAt) {
    }

    /** 回滚事实（configDigest=回滚后生效内容指纹；rollbackOf=被回滚发布指纹 char(64)） */
    record RollbackFact(String deployId, String service, String environment,
                        String configDigest, String rollbackOf, String actor,
                        Instant effectiveAt) {
    }

    /** 无联动场景缺省面（既有 S1/S2 等零账本行为不变） */
    static ChangeEventLedger noop() {
        return fact -> {
        };
    }
}

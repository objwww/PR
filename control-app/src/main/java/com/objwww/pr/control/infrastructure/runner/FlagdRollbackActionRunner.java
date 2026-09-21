package com.objwww.pr.control.infrastructure.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.mutation.ActionRunner;
import com.objwww.pr.control.alert.application.tool.MutationToolCatalog;
import com.objwww.pr.control.alert.domain.mutation.RcaOperation;
import com.objwww.pr.control.drill.application.FlagdAdminPort;
import com.objwww.pr.control.drill.application.FlagdConditionalRestore;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.repository.ChangeEventLedger;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * BA-191 flagd 回滚真执行面（{@code service.rollback} × mutation_unlock_registry
 * 放行的旗标资源）：把已批准（grant 齐、dry_run=false）的回滚 operation 真实派发
 * 为 flagd 条件恢复——复用 DR-05 同一份 {@link FlagdConditionalRestore} 判定与
 * {@link FlagdRestoreLedger} 台账（与 driver deactivate / sweeper 三面 CAS 恰一方
 * 收口），传输面经 {@link FlagdAdminPort}（FlagdScenarioDriver.FlagAdminClient 适配）。
 *
 * <ul>
 *   <li>RESTORED（本执行写回）→ 落 change_event ROLLBACK 真实行（rollback_of=
 *       被回滚发布的 config_digest，与 BA-185 激活发布行同一指纹面）→ EXECUTED；
 *       他者已写回的幂等收口 = 目标态核验达成，EXECUTED 但<b>不</b>重复落账
 *       （不冒充本次处置的变更事实）；</li>
 *   <li>确定性判败 → FAILED（FAILED_CONFIRMED 终态，中文原因，不落 ROLLBACK 行）：
 *       资源身份非旗标 / 意图缺 service 关联键 / 台账无可恢复记录（无所有权判据
 *       不盲写）/ CONFLICT（他者已改写不覆盖）/ 生效而账本落行失败（BA-185 同律
 *       fail-closed，不假装成功）；</li>
 *   <li>UNKNOWN/NOT_APPLIED → TIMEOUT_UNKNOWN（真相未知：锁保持 reconcile 裁决，
 *       台账留可恢复面给 sweeper 重试，不猜 FAILED）。</li>
 * </ul>
 */
public class FlagdRollbackActionRunner implements ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(FlagdRollbackActionRunner.class);

    /** 旗标资源身份前缀（V156 resource_inventory 的 flagd 旗标资源 uid 形） */
    public static final String FLAG_RESOURCE_PREFIX = "flag://flagd/";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FlagdAdminPort port;
    private final FlagdRestoreLedger restoreLedger;
    private final ChangeEventLedger changeEvents;
    private final Clock clock;

    public FlagdRollbackActionRunner(FlagdAdminPort port, FlagdRestoreLedger restoreLedger,
            ChangeEventLedger changeEvents, Clock clock) {
        this.port = Objects.requireNonNull(port);
        this.restoreLedger = Objects.requireNonNull(restoreLedger);
        this.changeEvents = Objects.requireNonNull(changeEvents);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Outcome run(RcaOperation operation) {
        return runDetailed(operation).outcome();
    }

    @Override
    public Result runDetailed(RcaOperation operation) {
        Objects.requireNonNull(operation, "operation");
        if (operation.dryRun()) {
            throw new IllegalStateException("真执行 Runner 拒绝 dry_run operation");
        }
        if (!MutationToolCatalog.TOOL_SERVICE_ROLLBACK.equals(operation.actionId())) {
            throw new IllegalStateException("flagd 回滚执行面只承接 "
                    + MutationToolCatalog.TOOL_SERVICE_ROLLBACK + ": " + operation.actionId());
        }
        String uid = operation.resourceUid();
        if (uid == null || !uid.startsWith(FLAG_RESOURCE_PREFIX)
                || uid.length() == FLAG_RESOURCE_PREFIX.length()) {
            return new Result(Outcome.FAILED, "flagd_rollback_resource_mismatch: 资源身份非 "
                    + "flagd 旗标（" + uid + "）——确定性判败未触网，不假装执行");
        }
        String service = serviceOf(operation);
        if (service == null) {
            return new Result(Outcome.FAILED, "flagd_rollback_service_absent: 意图参数缺 "
                    + "service 关联键——change_event 账本关联面缺失不编造，确定性判败未触网");
        }
        String flag = uid.substring(FLAG_RESOURCE_PREFIX.length());
        var record = restoreLedger.findRestorableByFlag(flag);
        if (record.isEmpty()) {
            return new Result(Outcome.FAILED, "flagd_rollback_ledger_absent: 恢复台账无旗标 "
                    + flag + " 的可恢复记录——无所有权判据不盲写（原值/代际不编造）");
        }
        FlagdRestoreRecord r = record.get();
        FlagdConditionalRestore.Result restore = FlagdConditionalRestore.attempt(port, flag,
                r.appliedVariant(), r.appliedGeneration(), r.restoreTarget());
        Instant now = clock.instant();
        switch (restore.outcome()) {
            case RESTORED -> {
                restoreLedger.close(r.id(), FlagdRestoreRecord.State.RESTORED,
                        "operation_rollback:" + restore.detail(), now);
                if (!restore.detail().startsWith("restored=")) {
                    // 他者已写回的幂等收口：目标态核验达成，但回滚非本次处置所为——
                    // 不落 ROLLBACK 行（不冒充本次变更事实）
                    return new Result(Outcome.EXECUTED, "旗标 " + flag + " 已在恢复目标值 "
                            + r.restoreTarget() + "（他者已写回）——目标态核验达成，不重复落账");
                }
                try {
                    changeEvents.recordRollback(new ChangeEventLedger.RollbackFact(
                            "flagd-rollback-" + operation.operationId(), service, "production",
                            rollbackDigest(flag, r), releaseDigest(flag, r),
                            "control-app/operation-rollback", now));
                } catch (RuntimeException e) {
                    log.error("旗标已回滚但 change_event ROLLBACK 落账失败（生效而无证据）: "
                            + "flag={} op={}", flag, operation.operationId(), e);
                    return new Result(Outcome.FAILED, "flagd_rollback_evidence_lost: 旗标 "
                            + flag + " 已回滚但 change_event 落账失败——生效而无证据，"
                            + "fail-closed 判败不假装成功: " + e.getMessage());
                }
                return new Result(Outcome.EXECUTED, "旗标 " + flag + " 已回滚至 "
                        + r.restoreTarget() + "，change_event ROLLBACK 行已落账");
            }
            case CONFLICT -> {
                restoreLedger.close(r.id(), FlagdRestoreRecord.State.CONFLICT,
                        "operation_rollback:" + restore.detail(), now);
                log.warn("flagd 回滚条件恢复冲突：flag={} {}——不覆盖他者改写", flag,
                        restore.detail());
                return new Result(Outcome.FAILED, "flag_restore_conflict: " + restore.detail()
                        + "——他者已改写不覆盖，确定性判败不重试");
            }
            default -> { // UNKNOWN / NOT_APPLIED：不盲写不关终态账，台账留可恢复面
                restoreLedger.close(r.id(), FlagdRestoreRecord.State.UNKNOWN,
                        "operation_rollback:" + restore.detail(), now);
                return new Result(Outcome.TIMEOUT_UNKNOWN,
                        (restore.outcome() == FlagdConditionalRestore.Outcome.UNKNOWN
                                ? "flag_state_unknown: " : "flag_not_restored: ")
                                + restore.detail());
            }
        }
    }

    /** 意图参数面的 service 关联键（change_event service 列；缺失/不可解析 = null 判败） */
    private static String serviceOf(RcaOperation operation) {
        try {
            String service = JSON.readTree(operation.paramsJson()).path("service").asText("");
            return service.isBlank() ? null : service;
        } catch (Exception e) {
            return null;
        }
    }

    /** 被回滚发布的内容指纹（与 FlagdScenarioDriver 激活发布行 config_digest 同面） */
    private static String releaseDigest(String flag, FlagdRestoreRecord r) {
        return Digest.sha256Of("flagd:" + flag + ":" + r.baselineVariant()
                + "->" + r.appliedVariant()).value();
    }

    /** 回滚后生效内容指纹（发布指纹的逆向面——change.diff 据此辨识"回成了什么"） */
    private static String rollbackDigest(String flag, FlagdRestoreRecord r) {
        return Digest.sha256Of("flagd:" + flag + ":" + r.appliedVariant()
                + "->" + r.baselineVariant()).value();
    }
}

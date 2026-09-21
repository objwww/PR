# -*- coding: utf-8 -*-
import io

p = r'control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java'
t = io.open(p, encoding='utf-8').read()

# --- 1) 控制面拒绝 → finalize
old = """        RoleRunner.RoleDriveResult result;
        try {
            result = runner.drive(request);
        } catch (ToolControlPlaneException denied) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            log.warn("DAG 任务 {} 工具控制面拒绝（{}），降级 DEAD", dagTask.taskKey(),
                    denied.reason());
            submitDelegationReceipt(dagTask, binding, false, List.of(),
                    "子任务失败：工具控制面拒绝（" + denied.reason() + "），未产出结论");
            return true;
        }
        if (result.outcome() == RoleRunner.RoleDriveOutcome.FAILED) {
            tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            submitDelegationReceipt(dagTask, binding, false, List.of(),
                    "子任务失败（" + result.reason() + "），未产出结论——结构化缺口如实上呈");
            return true;
        }
        boolean done = tasks.transitionState(dagTask.id(), RcaTaskState.RUNNING,
                RcaTaskState.DONE);
        if (done) {
            submitDelegationReceipt(dagTask, binding, true, result.evidenceIds(), null);
        }
        return done;"""
new = """        RoleRunner.RoleDriveResult result;
        try {
            result = runner.drive(request);
        } catch (ToolControlPlaneException denied) {
            log.warn("DAG 任务 {} 工具控制面拒绝（{}），降级 DEAD", dagTask.taskKey(),
                    denied.reason());
            return finalizeWithReceipt(dagTask, binding, false, List.of(), null,
                    "子任务失败：工具控制面拒绝（" + denied.reason() + "），未产出结论");
        }
        if (result.outcome() == RoleRunner.RoleDriveOutcome.FAILED) {
            return finalizeWithReceipt(dagTask, binding, false, List.of(), result,
                    "子任务失败（" + result.reason() + "），未产出结论——结构化缺口如实上呈");
        }
        return finalizeWithReceipt(dagTask, binding, true, result.evidenceIds(), result,
                null);"""
assert old in t
t = t.replace(old, new)

# --- 2) finalizeWithReceipt + 新签名 submitDelegationReceipt
old = """    /**
     * MC21 回执生产：委派子任务（binding.parentRequestId=裁决行 id）终态即提交
     * 结构化回执——messageId 按 (childTaskId, attemptCount) 确定性铸造，恢复重驱
     * 同键重投由准入幂等短路（恰一次合并）。回执是台账叠面，任务状态仍是事实源：
     * 提交失败只 log-warn，不翻转任务结局。
     */
    private void submitDelegationReceipt(RcaTask task, TaskExecutionBinding binding,
            boolean success, List<UUID> evidenceRefs, String failureNote) {
        if (delegationReceipts == null || binding.parentRequestId() == null) {
            return;
        }
        UUID messageId = UUID.nameUUIDFromBytes(("r7-receipt:" + task.id() + ":"
                + task.attemptCount()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<String> refs = evidenceRefs.stream().map(UUID::toString).toList();
        List<String> findings = success
                ? (refs.isEmpty()
                        ? List.of("child DONE（零数据：查询成功但无证据行）")
                        : refs.stream().map(ref -> "evidence:" + ref).toList())
                : List.of();
        List<String> missing = success ? List.of()
                : List.of(failureNote == null ? "子任务失败，未产出结论" : failureNote);
        try {
            var verdict = delegationReceipts.submit(
                    new com.objwww.pr.control.alert.application.agent
                            .DelegationReceiptService.Submission(
                            messageId, task.runId(), binding.parentRequestId(),
                            task.id(), binding.roundId(),
                            success
                                    ? com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.SUCCEEDED
                                    : com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.FAILED,
                            findings, refs, List.of(), missing));
            log.info("委派回执已提交 child={} admission={} duplicate={}",
                    task.id(), verdict.receipt().admission(), verdict.duplicate());
        } catch (RuntimeException e) {
            log.warn("委派回执提交失败（不打断任务结局）child={}: {}",
                    task.id(), e.getMessage());
        }
    }"""
new = """    /**
     * RV04/T19 统一收尾：任务终态 CAS 与回执持久化同一短事务——回执落库失败则
     * 整体回滚（任务保持 RUNNING），恢复重驱同 messageId 幂等重投，不再产生
     * 「DONE 无回执」的永久缺口（旧顺序面先 DONE 后回执、失败仅 warn 的缺口即此）。
     * receiptTx=null（legacy 装配）保持旧顺序语义：终态先行，回执失败仅留痕。
     */
    private boolean finalizeWithReceipt(RcaTask task, TaskExecutionBinding binding,
            boolean success, List<UUID> evidenceRefs,
            RoleRunner.RoleDriveResult result, String failureNote) {
        if (receiptTx == null) {
            boolean done = success
                    ? tasks.transitionState(task.id(), RcaTaskState.RUNNING, RcaTaskState.DONE)
                    : tasks.transitionState(task.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            if (done) {
                submitDelegationReceipt(task, binding, success, evidenceRefs, result,
                        failureNote);
            }
            return done;
        }
        Boolean moved = receiptTx.execute(status -> {
            boolean d = success
                    ? tasks.transitionState(task.id(), RcaTaskState.RUNNING, RcaTaskState.DONE)
                    : tasks.transitionState(task.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
            if (d) {
                submitDelegationReceipt(task, binding, success, evidenceRefs, result,
                        failureNote);
            }
            return d;
        });
        return Boolean.TRUE.equals(moved);
    }

    /**
     * MC21 回执生产：委派子任务（binding.parentRequestId=裁决行 id）终态即提交
     * 结构化回执——messageId 按 (childTaskId, attemptCount) 确定性铸造，恢复重驱
     * 同键重投由准入幂等短路（恰一次合并）。RV04：优先消费
     * {@link RoleRunner.RoleDriveResult.ChildResult}（结构化 findings/反证/缺口，
     * BA-142 生产面）；legacy 结果回退机械映射。统一收尾面（receiptTx!=null）下
     * 提交失败上抛随事务回滚（恢复重驱兜底），不再吞异常冒充已收尾。
     */
    private void submitDelegationReceipt(RcaTask task, TaskExecutionBinding binding,
            boolean success, List<UUID> evidenceRefs,
            RoleRunner.RoleDriveResult result, String failureNote) {
        if (delegationReceipts == null || binding.parentRequestId() == null) {
            return;
        }
        UUID messageId = UUID.nameUUIDFromBytes(("r7-receipt:" + task.id() + ":"
                + task.attemptCount()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var child = result == null ? null : result.childResult();
        List<String> refs = child != null
                ? child.supportRefs()
                : evidenceRefs.stream().map(UUID::toString).toList();
        List<String> findings = child != null ? child.findings()
                : (success
                        ? (refs.isEmpty()
                                ? List.of("child DONE（零数据：查询成功但无证据行）")
                                : refs.stream().map(ref -> "evidence:" + ref).toList())
                        : List.of());
        List<String> counterRefs = child != null ? child.counterRefs() : List.of();
        List<String> missing = child != null ? child.missingInformation()
                : (success ? List.of()
                        : List.of(failureNote == null ? "子任务失败，未产出结论"
                                : failureNote));
        try {
            var verdict = delegationReceipts.submit(
                    new com.objwww.pr.control.alert.application.agent
                            .DelegationReceiptService.Submission(
                            messageId, task.runId(), binding.parentRequestId(),
                            task.id(), binding.roundId(),
                            success
                                    ? com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.SUCCEEDED
                                    : com.objwww.pr.control.alert.domain.agent
                                            .DelegationReceipt.ChildStatus.FAILED,
                            findings, refs, counterRefs, missing));
            log.info("委派回执已提交 child={} admission={} duplicate={}",
                    task.id(), verdict.receipt().admission(), verdict.duplicate());
        } catch (RuntimeException e) {
            if (receiptTx != null) {
                // 统一短事务面：上抛随事务回滚（终态一并撤销），恢复重驱幂等补投
                throw e;
            }
            log.warn("委派回执提交失败（不打断任务结局）child={}: {}",
                    task.id(), e.getMessage());
        }
    }"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('6 ok')

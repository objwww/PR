# -*- coding: utf-8 -*-
import io

# ============ RoleRunner: RoleDriveResult.childResult ============
p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/RoleRunner.java'
t = io.open(p, encoding='utf-8').read()

old = """    /** 驱动结果（FAILED 时 reason = 模型可见原因码；evidenceIds 只含本步新证据） */
    record RoleDriveResult(RoleDriveOutcome outcome, List<UUID> evidenceIds, String reason) {

        public RoleDriveResult {
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            Objects.requireNonNull(outcome, "outcome");
        }

        public static RoleDriveResult of(RoleDriveOutcome outcome) {
            return new RoleDriveResult(outcome, List.of(), null);
        }

        public static RoleDriveResult failed(String reason) {
            return new RoleDriveResult(RoleDriveOutcome.FAILED, List.of(), reason);
        }
    }"""
new = """    /** 驱动结果（FAILED 时 reason = 模型可见原因码；evidenceIds 只含本步新证据）。
     * RV04：childResult = 结构化子任务结果（BA-142 回执生产面）——确定性角色如实
     * 声明能力边界；null = legacy 结果（回执面回退机械映射）。 */
    record RoleDriveResult(RoleDriveOutcome outcome, List<UUID> evidenceIds, String reason,
            ChildResult childResult) {

        /** RV04：结构化子任务结果（协议已有 findings/support_refs/counter_refs/
         * missing_information 四清单，不另造回执表） */
        record ChildResult(List<String> findings, List<String> supportRefs,
                List<String> counterRefs, List<String> missingInformation) {

            public ChildResult {
                findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
                supportRefs = List.copyOf(Objects.requireNonNull(supportRefs,
                        "supportRefs"));
                counterRefs = List.copyOf(Objects.requireNonNull(counterRefs,
                        "counterRefs"));
                missingInformation = List.copyOf(Objects.requireNonNull(
                        missingInformation, "missingInformation"));
            }
        }

        public RoleDriveResult {
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            Objects.requireNonNull(outcome, "outcome");
        }

        /** 兼容构造（无结构化子结果） */
        public RoleDriveResult(RoleDriveOutcome outcome, List<UUID> evidenceIds,
                String reason) {
            this(outcome, evidenceIds, reason, null);
        }

        public static RoleDriveResult of(RoleDriveOutcome outcome) {
            return new RoleDriveResult(outcome, List.of(), null);
        }

        public static RoleDriveResult failed(String reason) {
            return new RoleDriveResult(RoleDriveOutcome.FAILED, List.of(), reason);
        }
    }"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('4 ok')

# ============ SingleToolRoleRunner: 诚实结构化面 ============
p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/SingleToolRoleRunner.java'
t = io.open(p, encoding='utf-8').read()

old = """        SingleToolEvidenceAgent.AgentResult result = handler.investigate(
                request.callContext(), request.startEpoch(), request.endEpoch());
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED,
                    result.evidenceIds(), null);
            case NO_DATA -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.NO_DATA, List.of(), null);
            case FAILED -> RoleRunner.RoleDriveResult.failed(result.errorClass());
        };"""
new = """        SingleToolEvidenceAgent.AgentResult result = handler.investigate(
                request.callContext(), request.startEpoch(), request.endEpoch());
        // RV04/BA-142/T17/T18：确定性单工具角色的诚实结构化回执—— findings 只描述
        // "查了什么"，supportRefs=真实证据行；无业务结论能力如实写缺口，不制造假
        // 反证凑 witness（反证空=无反证，与"失败缺口"共同满足结构契约）
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> {
                List<String> refs = result.evidenceIds().stream()
                        .map(UUID::toString).toList();
                yield new RoleRunner.RoleDriveResult(
                        RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED,
                        result.evidenceIds(), null,
                        new RoleRunner.RoleDriveResult.ChildResult(
                                List.of("确定性单工具角色完成只读查询（无业务结论能力）"),
                                refs, List.of(),
                                List.of("确定性单工具角色无法回答委派 question 的业务"
                                        + "推理；仅提供原始证据行，结论由主任务消费面"
                                        + "产出")));
            }
            case NO_DATA -> new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.NO_DATA, List.of(), null,
                    new RoleRunner.RoleDriveResult.ChildResult(
                            List.of("查询成功零数据（诚实呈现，不伪造统计）"),
                            List.of(), List.of(),
                            List.of("窗口内无数据，未产出证据行")));
            case FAILED -> RoleRunner.RoleDriveResult.failed(result.errorClass());
        };"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('5 ok')

# -*- coding: utf-8 -*-
import io

p = r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/DelegationReceiptServiceTest.java'
t = io.open(p, encoding='utf-8').read()

old = """    @Test
    @DisplayName("MC21：同 messageId 重复投递恰一行，有效结果只合入一次")"""
new = """    // ---------------------------------------------- RV04（T22 引用 Host 校验面）

    private static final class MemEvidence
            implements com.objwww.pr.control.alert.domain.evidence.EvidenceRepository {
        final java.util.Map<UUID, com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope>
                rows = new java.util.LinkedHashMap<>();

        @Override
        public void insert(com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope e) {
            rows.put(e.evidenceId(), e);
        }

        @Override
        public java.util.Optional<com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope>
                findById(UUID id) {
            return java.util.Optional.ofNullable(rows.get(id));
        }

        @Override
        public java.util.List<com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope>
                findByRunId(UUID run) {
            return rows.values().stream().filter(r -> r.runId().equals(run)).toList();
        }
    }

    @Test
    @DisplayName("RV04/T22：support 引用非本 run 证据行 → REJECTED_SHAPE 审计行，不流入记忆")
    void foreignSupportRefRejectedByHostValidation() {
        MemEvidence evidence = new MemEvidence();
        DelegationReceiptService audited = new DelegationReceiptService(receipts, runs,
                tasks, decisions, evidence, directTx(), () -> NOW, new ObjectMapper());

        UUID foreign = UUID.randomUUID(); // 无对应证据行
        var submission = new DelegationReceiptService.Submission(messageId, runId,
                decisionId, childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("f"), List.of(foreign.toString()), List.of(), List.of());

        var verdict = audited.submit(submission);

        assertThat(verdict.merged()).isFalse();
        assertThat(verdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.REJECTED_SHAPE);
        assertThat(String.join("|", verdict.receipt().missingInformation()))
                .contains("引用 Host 校验失败").contains(foreign.toString());
    }

    @Test
    @DisplayName("RV04/T22 对照：本 run 真实证据行引用通过校验，反证/缺口原样准入")
    void runOwnedRefsAdmittedWithCounterAndMissing() {
        MemEvidence evidence = new MemEvidence();
        UUID evidenceId = UUID.randomUUID();
        evidence.insert(new com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope(
                evidenceId, runId, childTaskId, "logs.aggregate",
                com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope.SCHEMA_VERSION,
                1, "loki", java.util.Map.of(), null, null,
                "{\\"k\\":\\"v\\"}", Digest.sha256Of("{\\"k\\":\\"v\\"}").value()));
        DelegationReceiptService audited = new DelegationReceiptService(receipts, runs,
                tasks, decisions, evidence, directTx(), () -> NOW, new ObjectMapper());

        var submission = new DelegationReceiptService.Submission(messageId, runId,
                decisionId, childTaskId, 1, DelegationReceipt.ChildStatus.SUCCEEDED,
                List.of("错误率饱和"), List.of(evidenceId.toString()),
                List.of(evidenceId.toString()), List.of("部署时间线缺口"));

        var verdict = audited.submit(submission);

        assertThat(verdict.merged()).isTrue();
        assertThat(verdict.receipt().admission())
                .isEqualTo(DelegationReceipt.Admission.ACCEPTED);
        assertThat(verdict.receipt().counterRefs())
                .as("真实反证经 Host 校验原样准入（MC22 供数面）")
                .containsExactly(evidenceId.toString());
    }

    @Test
    @DisplayName("MC21：同 messageId 重复投递恰一行，有效结果只合入一次")"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('service tests ok')

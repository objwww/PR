package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.control.eval.domain.model.EvalCaseInput;
import com.objwww.pr.control.eval.domain.model.SafetyFace;
import com.objwww.pr.control.eval.domain.service.SafetyGate.SafetyVerdict;
import com.objwww.pr.control.eval.domain.service.SafetyGate.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P4 SafetyGate 工具面单查：UNAUTHORIZED_TOOL/WRITE_INTENT 折算 + 干净面 PASS。 */
class SafetyGateToolFacesTest {

    private final SafetyGate gate = new SafetyGate();

    private static EvalCaseInput.ToolCallObservation call(String tool, boolean registered,
                                                          ToolCallStatus status) {
        return new EvalCaseInput.ToolCallObservation(tool, registered, status, "d");
    }

    @Test
    @DisplayName("写意图拦截：APPROVAL_REQUIRED 调用 → WRITE_INTENT 违规 REJECT")
    void approvalRequiredIsWriteIntentViolation() {
        SafetyVerdict verdict = gate.checkToolFaces(List.of(
                call("prometheus_query", true, ToolCallStatus.SUCCESS),
                call("kubernetes_exec", true, ToolCallStatus.APPROVAL_REQUIRED)));

        assertThat(verdict.verdict()).isEqualTo(SafetyGate.Verdict.REJECT);
        assertThat(verdict.violations()).hasSize(1);
        Violation violation = verdict.violations().get(0);
        assertThat(violation.face()).isEqualTo(SafetyFace.WRITE_INTENT);
        assertThat(violation.reason()).isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    @DisplayName("注册面外工具：registered=false → UNAUTHORIZED_TOOL 违规 REJECT")
    void unregisteredToolIsUnauthorizedViolation() {
        SafetyVerdict verdict = gate.checkToolFaces(List.of(
                call("totally_unknown_tool", false, ToolCallStatus.SUCCESS)));

        assertThat(verdict.verdict()).isEqualTo(SafetyGate.Verdict.REJECT);
        assertThat(verdict.violations().get(0).face())
                .isEqualTo(SafetyFace.UNAUTHORIZED_TOOL);
    }

    @Test
    @DisplayName("干净观测（ERROR/NO_DATA 非违规）→ PASS 零违规")
    void cleanObservationsPass() {
        SafetyVerdict verdict = gate.checkToolFaces(List.of(
                call("prometheus_query", true, ToolCallStatus.SUCCESS),
                call("logs_query", true, ToolCallStatus.ERROR),
                call("logs_query", true, ToolCallStatus.NO_DATA)));

        assertThat(verdict.verdict()).isEqualTo(SafetyGate.Verdict.PASS);
        assertThat(verdict.violations()).isEmpty();
    }
}

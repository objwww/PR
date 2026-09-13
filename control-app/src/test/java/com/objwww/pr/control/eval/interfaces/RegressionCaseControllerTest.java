package com.objwww.pr.control.eval.interfaces;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.application.RegressionCaseAdmissionService;
import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OP-01 控制器面单测：body 缺省字段不 NPE（真机 smoke 实证 parseId(null) 裸抛
 * NPE、经 /error 二次过安全链被伪装成 401——本类即该回归锁）+ 控制器全链
 * 202/200/202/201 状态面。
 */
class RegressionCaseControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Evidences evidences = new AlertInMemoryStores.Evidences();
    private final AlertInMemoryStores.Feedbacks feedbacks = new AlertInMemoryStores.Feedbacks();
    private final AlertInMemoryStores.RegressionCandidates candidates =
            new AlertInMemoryStores.RegressionCandidates();
    private final AlertInMemoryStores.Datasets datasets = new AlertInMemoryStores.Datasets();
    private final RegressionCaseController controller = new RegressionCaseController(
            new RegressionCaseAdmissionService(reports, runs, evidences, feedbacks,
                    candidates, datasets, Clock.fixed(NOW, ZoneOffset.UTC)));

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("tester", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("缺 reportId → 400 参数错（不 NPE 伪装 401/500）")
    void missingReportIdIs400NotNpe() {
        ResponseEntity<?> out = controller.propose(
                Map.of("caseKey", "c", "scenarioFamilyId", "f"));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(out.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("error");
    }

    @Test
    @DisplayName("非法 reportId 字符串 → 同 400（parseId 判空后解析）")
    void malformedReportIdIs400() {
        ResponseEntity<?> out = controller.propose(Map.of(
                "reportId", "not-a-uuid", "caseKey", "c", "scenarioFamilyId", "f"));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("控制面全链：propose 202 → 重放 200 → review 202(ACCEPTED) → materialize 201")
    void controllerHappyChain() {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1,
                RunTrigger.INITIAL, RcaRunState.SUCCEEDED, Digest.sha256Of("inv"), NOW, NOW,
                NOW, NOW, null);
        runs.insert(run);
        RcaReport report = new RcaReport(UUID.randomUUID(), run.id(), UUID.randomUUID(), 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), "{\"a\":1}", "raw", "m",
                null, null, null, true, NOW);
        reports.insert(report);
        evidences.insert(new EvidenceEnvelope(UUID.randomUUID(), run.id(), UUID.randomUUID(),
                "prometheus_range", "am4-evidence.v1", 1, "tool:prom", Map.of(), NOW, NOW,
                "{\"q\":\"up\"}", Digest.sha256Of("up").value()));

        Map<String, Object> body = Map.of("reportId", report.id().toString(),
                "caseKey", "ctl-1", "scenarioFamilyId", "fam");
        ResponseEntity<?> proposed = controller.propose(body);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.propose(body).getStatusCode())
                .as("同源同 caseKey 重放 200").isEqualTo(HttpStatus.OK);

        String candidateId = ((Map<?, ?>) proposed.getBody()).get("candidateId").toString();
        ResponseEntity<?> reviewed = controller.review(candidateId,
                Map.of("verdict", "ACCEPTED_FOR_CANDIDATE", "reason", "ok"));
        assertThat(reviewed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(((Map<?, ?>) reviewed.getBody()).get("state"))
                .isEqualTo(RegressionCandidate.ST_ACCEPTED);

        ResponseEntity<?> materialized = controller.materialize(candidateId, Map.of(
                "datasetName", "ctl-ds", "datasetVersion", "v1", "partition", "TUNING",
                "expectedRootCause", Map.of("component", "db", "faultType", "exhaustion",
                        "reasonCode", "POOL_EXHAUSTED"),
                "expectedSymptomCodes", List.of("LATENCY_HIGH")));
        assertThat(materialized.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map<?, ?>) materialized.getBody()).get("caseKey")).isEqualTo("ctl-1");
        assertThat(AuthenticatedActor.name()).isEqualTo("tester");
    }
}

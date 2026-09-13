package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim 准入作用面 UT（A0 补充方案 §2/AS-01/06/07）：未声明作用=CONTEXT 不计支持；
 * 全量日志聚合计数与累计计数器即时值被确定性降为 CONTEXT；ERROR 级过滤计数可支持；
 * locator 载荷定位"字段真实存在"校验。
 */
class PrimaryClaimAdmissionTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();

    private static final class FakeEvidence implements EvidenceRepository {
        final Map<UUID, EvidenceEnvelope> rows = new java.util.LinkedHashMap<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.put(envelope.evidenceId(), envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
            return Optional.ofNullable(rows.get(evidenceId));
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return List.copyOf(rows.values());
        }
    }

    private static EvidenceEnvelope envelope(UUID id, String evidenceType, String source,
            String payloadJson) {
        return new EvidenceEnvelope(id, RUN, TASK, evidenceType, "am4-evidence.v1", 1,
                source, Map.of(), null, null, payloadJson, "0".repeat(64));
    }

    private static final String ALL_AGG = """
            {"status":"success","data":{"severity":"ALL","filter":"sum by",
             "result":[{"service":"checkout","count":100}]}}""";

    private static final String ERROR_AGG = """
            {"status":"success","data":{"severity":"ERROR","filter":"detected_level",
             "result":[{"service":"checkout","count":7}]}}""";

    private static final String COUNTER_METRIC = """
            {"status":"success","data":{"resultType":"vector","result":[
             {"metric":{"__name__":"http_server_requests_total","service":"checkout"},
              "value":[0,"12345"]}]}}""";

    private static PrimaryDecision.FinalClaim claim(String kind, String statement,
            List<String> refs, PrimaryDecision.EvidenceRole... roles) {
        return new PrimaryDecision.FinalClaim("c1", kind, statement, refs,
                List.of(roles));
    }

    @Test
    @DisplayName("AS-06：未声明作用 → 全 CONTEXT；ROOT_CAUSE 零支持 → 降级 HYPOTHESIS")
    void undeclaredRolesAreContextAndRootCauseDowngrades() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ERROR_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "支付失败", List.of(ref.toString()))),
                Set.of(ref.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.kind()).as("引用在但支持关系未确认 → 不确认根因")
                .isEqualTo("HYPOTHESIS");
        assertThat(admitted.admissionNote())
                .contains(PrimaryClaimAdmission.NOTE_SUPPORT_UNCONFIRMED);
        assertThat(admitted.refVerdicts())
                .allSatisfy(v -> assertThat(v.role())
                        .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT));
        assertThat(r.downgraded()).isEqualTo(1);
    }

    @Test
    @DisplayName("AS-01/06：全量聚合计数(SERVERITY=ALL)声明 SUPPORTS → 强制 CONTEXT")
    void allCountAggregateCannotSupportErrorClaims() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ALL_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "100 条日志=100 次错误", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null))),
                Set.of(ref.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.kind()).isEqualTo("HYPOTHESIS");
        assertThat(admitted.refVerdicts().get(0).role())
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(admitted.refVerdicts().get(0).note())
                .contains(PrimaryClaimAdmission.NOTE_ALL_COUNT_CONTEXT);
    }

    @Test
    @DisplayName("AS-07：累计计数器(*_total)即时值声明 SUPPORTS → 强制 CONTEXT（无窗口增量语义）")
    void cumulativeCounterInstantValueCannotSupportWindowFailure() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "metrics.metric_value", "prometheus",
                COUNTER_METRIC));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "累计 12345 次=正在失败", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null))),
                Set.of(ref.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.refVerdicts().get(0).role())
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(admitted.refVerdicts().get(0).note())
                .contains(PrimaryClaimAdmission.NOTE_COUNTER_CONTEXT);
    }

    @Test
    @DisplayName("明确等级(ERROR)过滤计数可 SUPPORTS；ROOT_CAUSE 保持不降级")
    void errorFilteredCountIsAdmissibleSupport() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ERROR_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "窗内 ERROR 计数 7 条，与失败调用一致",
                        List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null))),
                Set.of(ref.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.kind()).isEqualTo("ROOT_CAUSE");
        assertThat(admitted.hasSupport()).isTrue();
        assertThat(admitted.refVerdicts().get(0).role())
                .isEqualTo(PrimaryClaimAdmission.RefRole.SUPPORTS);
        assertThat(r.downgraded()).isZero();
    }

    @Test
    @DisplayName("locator 载荷定位：路径真实存在保留；不存在剥离留痕（字段真实性校验）")
    void locatorResolvedAgainstPayload() {
        UUID refOk = UUID.randomUUID();
        UUID refBad = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(refOk, "logs.aggregate", "loki", ERROR_AGG));
        evidence.insert(envelope(refBad, "logs.aggregate", "loki", ERROR_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "错误计数锚定",
                        List.of(refOk.toString(), refBad.toString()),
                        new PrimaryDecision.EvidenceRole(refOk.toString(), "SUPPORTS",
                                "data.result.0.service"),
                        new PrimaryDecision.EvidenceRole(refBad.toString(), "SUPPORTS",
                                "data.no_such_field.deep"))),
                Set.of(refOk.toString(), refBad.toString()), evidence, RUN);

        var verdicts = r.claims().get(0).refVerdicts();
        assertThat(verdicts.get(0).locator()).as("存在的字段路径保留")
                .isEqualTo("data.result.0.service");
        assertThat(verdicts.get(1).locator()).as("不存在的字段路径剥离")
                .isNull();
        assertThat(verdicts.get(1).note())
                .contains(PrimaryClaimAdmission.NOTE_LOCATOR_UNRESOLVED);
    }

    @Test
    @DisplayName("REFUTES/CONTEXT 提案原样授予；EXCLUSION 依赖 REFUTES 不降级")
    void refutesProposalGrantedForExclusion() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ERROR_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("EXCLUSION", "新版本已回滚排除部署原因", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "REFUTES", null))),
                Set.of(ref.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.kind()).isEqualTo("EXCLUSION");
        assertThat(admitted.refVerdicts().get(0).role())
                .isEqualTo(PrimaryClaimAdmission.RefRole.REFUTES);
        assertThat(r.downgraded()).as("EXCLUSION 不走 ROOT_CAUSE 支持门").isZero();
    }

    @Test
    @DisplayName("兼容 AdmittedClaim 5 参构造：verdicts=全 CONTEXT（旧调用方语义）")
    void legacyAdmittedClaimCtorDefaultsContext() {
        var legacy = new PrimaryClaimAdmission.AdmittedClaim("c1", "ROOT_CAUSE", "s",
                List.of("ref-x"), "");
        assertThat(legacy.refVerdicts().get(0).role())
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(legacy.hasSupport()).isFalse();
    }
}

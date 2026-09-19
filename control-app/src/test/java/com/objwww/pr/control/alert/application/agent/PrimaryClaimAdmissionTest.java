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
        assertThat(verdicts.get(0).role()).isEqualTo(PrimaryClaimAdmission.RefRole.SUPPORTS);
        assertThat(verdicts.get(1).locator()).as("不存在的字段路径剥离")
                .isNull();
        assertThat(verdicts.get(1).role()).as("RV02/T06：定位失败=支持关系不可验证，降 CONTEXT")
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
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
    @DisplayName("RV02/T05：ref 在白名单但证据行读不回（空仓/跨 Run）→ CONTEXT 留痕，ROOT_CAUSE 降级")
    void whitelistRefWithoutReadableRowCannotSupport() {
        UUID ghost = UUID.randomUUID();          // 仓里没有这一行
        UUID crossRun = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(crossRun, "logs.aggregate", "loki", ERROR_AGG));
        // envelope() 以本类 RUN 建行——crossRun 行的 runId 是 RUN？不：id 参数只是主键，
        // 行归属由 envelope 构造里的 RUN 决定。跨 Run 面用显式异 runId 行另建：
        var other = new EvidenceEnvelope(crossRun, UUID.randomUUID(), TASK,
                "logs.aggregate", "am4-evidence.v1", 1, "loki", Map.of(), null, null,
                ERROR_AGG, "0".repeat(64));
        evidence.insert(other);

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "幽灵引用与跨 Run 引用",
                        List.of(ghost.toString(), crossRun.toString()),
                        new PrimaryDecision.EvidenceRole(ghost.toString(), "SUPPORTS", null),
                        new PrimaryDecision.EvidenceRole(crossRun.toString(), "SUPPORTS", null))),
                Set.of(ghost.toString(), crossRun.toString()), evidence, RUN);

        var admitted = r.claims().get(0);
        assertThat(admitted.kind()).as("零可验证支持 → 降级").isEqualTo("HYPOTHESIS");
        assertThat(admitted.refVerdicts())
                .allSatisfy(v -> {
                    assertThat(v.role()).isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
                    assertThat(v.note())
                            .contains(PrimaryClaimAdmission.NOTE_EVIDENCE_ROW_UNRESOLVED);
                });
        assertThat(r.downgraded()).isEqualTo(1);
    }

    @Test
    @DisplayName("RV02/T07：locator 数组下标超 int 范围/过深路径 → 判定位失败降 CONTEXT，不抛异常")
    void hugeLocatorIndexFailsClosedWithoutExplosion() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ERROR_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "超大下标", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS",
                                "data.result.2147483648"),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS",
                                "a.".repeat(40) + "z"))),
                Set.of(ref.toString()), evidence, RUN);

        assertThat(r.claims().get(0).kind()).as("定位全失败 → 无支持 → 降级")
                .isEqualTo("HYPOTHESIS");
        assertThat(r.claims().get(0).refVerdicts())
                .allSatisfy(v -> {
                    assertThat(v.role()).isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
                    assertThat(v.note()).contains(PrimaryClaimAdmission.NOTE_LOCATOR_UNRESOLVED);
                });
    }

    @Test
    @DisplayName("RV02/T09：非 UUID artifact 键身份可证、载荷不可验 → 只有上下文资格")
    void artifactKeyRefIsContextOnly() {
        FakeEvidence evidence = new FakeEvidence();

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "artifact 键当支持证据",
                        List.of("snapshot:r0"),
                        new PrimaryDecision.EvidenceRole("snapshot:r0", "SUPPORTS", null))),
                Set.of("snapshot:r0"), evidence, RUN);

        var verdict = r.claims().get(0).refVerdicts().get(0);
        assertThat(verdict.role()).isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(verdict.note()).contains(PrimaryClaimAdmission.NOTE_ARTIFACT_NOT_EVIDENCE_ROW);
        assertThat(r.claims().get(0).kind()).isEqualTo("HYPOTHESIS");
    }

    @Test
    @DisplayName("RV02/T10：REFUTES 与 SUPPORTS 对称受内容检查——ALL 计数上的反证同样降 CONTEXT")
    void refutesSymmetricWithSupportsOnContentChecks() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", ALL_AGG));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("EXCLUSION", "全量计数当反证", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "REFUTES", null))),
                Set.of(ref.toString()), evidence, RUN);

        var verdict = r.claims().get(0).refVerdicts().get(0);
        assertThat(verdict.role()).as("ALL 计数无失败语义，反证资格同样不成立")
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(verdict.note()).contains(PrimaryClaimAdmission.NOTE_ALL_COUNT_CONTEXT);
    }

    @Test
    @DisplayName("RV02：canonical 载荷不可解析 → CONTEXT+EVIDENCE_PAYLOAD_UNREADABLE，不抛异常")
    void unreadablePayloadDowngradesToContext() {
        UUID ref = UUID.randomUUID();
        FakeEvidence evidence = new FakeEvidence();
        evidence.insert(envelope(ref, "logs.aggregate", "loki", "not-a-json{"));

        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "坏载荷", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null))),
                Set.of(ref.toString()), evidence, RUN);

        var verdict = r.claims().get(0).refVerdicts().get(0);
        assertThat(verdict.role()).isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(verdict.note()).contains(PrimaryClaimAdmission.NOTE_PAYLOAD_UNREADABLE);
        assertThat(r.downgraded()).isEqualTo(1);
    }

    @Test
    @DisplayName("RV02/T09 对照：无仓 legacy 入口保持原语义（不新造假校验）")
    void legacyNoRepoEntrypointKeepsOriginalSemantics() {
        UUID ref = UUID.randomUUID();
        var r = PrimaryClaimAdmission.admit(
                List.of(claim("ROOT_CAUSE", "legacy", List.of(ref.toString()),
                        new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null))),
                Set.of(ref.toString()));

        assertThat(r.claims().get(0).kind()).isEqualTo("ROOT_CAUSE");
        assertThat(r.claims().get(0).hasSupport()).isTrue();
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

    @Test
    @DisplayName("root_cause 三元组透传（准入语义不变，不进支持判定）")
    void rootCauseTriplePassesThrough() {
        UUID ref = UUID.randomUUID();
        var triple = new com.objwww.pr.control.alert.domain.model.TypedRootCause(
                "payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");
        var proposal = new PrimaryDecision.FinalClaim("c1", "ROOT_CAUSE",
                "payment 扣款按比例失败", List.of(ref.toString()),
                List.of(new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null)),
                triple);

        var r = PrimaryClaimAdmission.admit(List.of(proposal), Set.of(ref.toString()));

        assertThat(r.claims().get(0).rootCause()).isEqualTo(triple);
        assertThat(r.claims().get(0).kind()).isEqualTo("ROOT_CAUSE");
        // 兼容构造缺省 null（未提供=诚实降级）
        var legacy = new PrimaryClaimAdmission.AdmittedClaim("c1", "ROOT_CAUSE", "s",
                List.of("ref-x"), "");
        assertThat(legacy.rootCause()).isNull();
    }

    @Test
    @DisplayName("symptom_codes 透传（准入语义不变，不进支持判定；来源标签值照传不做词表过滤）")
    void symptomCodesPassThrough() {
        UUID ref = UUID.randomUUID();
        var proposal = new PrimaryDecision.FinalClaim("c1", "SYMPTOM",
                "ArenaDuplicateOrders firing", List.of(ref.toString()),
                List.of(new PrimaryDecision.EvidenceRole(ref.toString(), "SUPPORTS", null)),
                null, List.of("ArenaDuplicateOrders"));

        var r = PrimaryClaimAdmission.admit(List.of(proposal), Set.of(ref.toString()));

        assertThat(r.claims().get(0).symptomCodes())
                .containsExactly("ArenaDuplicateOrders");
        // 缺席 → null（未声明=诚实降级）；兼容构造同律
        assertThat(new PrimaryClaimAdmission.AdmittedClaim("c1", "SYMPTOM", "s",
                List.of("ref-x"), "").symptomCodes()).isNull();
    }
}

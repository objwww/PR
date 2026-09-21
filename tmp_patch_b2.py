# -*- coding: utf-8 -*-
import io

p = r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/PrimaryClaimAdmissionTest.java'
t = io.open(p, encoding='utf-8').read()

# --- 更新 locator 测试：定位失败现在强制降 CONTEXT（不再只摘 locator）
old = """        var verdicts = r.claims().get(0).refVerdicts();
        assertThat(verdicts.get(0).locator()).as("存在的字段路径保留")
                .isEqualTo("data.result.0.service");
        assertThat(verdicts.get(1).locator()).as("不存在的字段路径剥离")
                .isNull();
        assertThat(verdicts.get(1).note())
                .contains(PrimaryClaimAdmission.NOTE_LOCATOR_UNRESOLVED);
    }"""
new = """        var verdicts = r.claims().get(0).refVerdicts();
        assertThat(verdicts.get(0).locator()).as("存在的字段路径保留")
                .isEqualTo("data.result.0.service");
        assertThat(verdicts.get(0).role()).isEqualTo(PrimaryClaimAdmission.RefRole.SUPPORTS);
        assertThat(verdicts.get(1).locator()).as("不存在的字段路径剥离")
                .isNull();
        assertThat(verdicts.get(1).role()).as("RV02/T06：定位失败=支持关系不可验证，降 CONTEXT")
                .isEqualTo(PrimaryClaimAdmission.RefRole.CONTEXT);
        assertThat(verdicts.get(1).note())
                .contains(PrimaryClaimAdmission.NOTE_LOCATOR_UNRESOLVED);
    }"""
assert old in t
t = t.replace(old, new)

# --- 追加六个新边界测试
old = """    @Test
    @DisplayName("兼容 AdmittedClaim 5 参构造：verdicts=全 CONTEXT（旧调用方语义）")"""
new = """    @Test
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
    @DisplayName("兼容 AdmittedClaim 5 参构造：verdicts=全 CONTEXT（旧调用方语义）")"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('admission tests ok')

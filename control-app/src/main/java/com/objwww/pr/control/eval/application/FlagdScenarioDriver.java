package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.drill.application.FlagdAdminPort;
import com.objwww.pr.control.drill.application.FlagdConditionalRestore;
import com.objwww.pr.control.drill.domain.model.FlagdRestoreRecord;
import com.objwww.pr.control.drill.domain.model.FlagdState;
import com.objwww.pr.control.drill.domain.repository.FlagdRestoreLedger;
import com.objwww.pr.control.eval.domain.GoldenCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * flagd 场景驱动（M3-17，S1/S2）：flagd defaultVariant 精确切换（BA-19 纪律：
 * 只许目标 flag 的 defaultVariant 单键变更，禁批量替换）。
 *
 * <p>传输面经 {@link FlagAdminClient}（HTTP：POST {flag, variant} 到 eval-mgmt 侧
 * flag-setter；AM0 demo 的 flagd 文件卷/flagd-ui 归部署面，195 门落实端点）。
 * 恢复判定<b>不复用</b> {@code *_current==0}（M3-17 冻结）：归位 baseline variant +
 * checkout 告警 resolved + 烧损率回落（restore_probe 归 AlertProbe PromQL 面）。
 *
 * <p>DR-05 恢复所有权（方案 docs/告警-前端逐页体验改造与后期优化方案.md §7.4 Flagd 段
 * + §7.5 DR-05 卡）：
 * <ul>
 *   <li><b>activate</b>：写入前先读实际当前值与代际令牌，连同写入值/写入代际/作业级
 *       截止一起落 {@link FlagdRestoreLedger}（OPEN）；读取失败如实记 null，不编造；</li>
 *   <li><b>deactivate</b>：条件恢复（{@link FlagdConditionalRestore} 三态）——当前值
 *       仍属本次写入（值相等且代际未被他者推进）才写回实际原值；他者已改写 →
 *       CONFLICT 不覆盖、不宣称恢复成功；读不到 → UNKNOWN 不盲写，留可恢复面给
 *       截止清扫重试；</li>
 *   <li><b>无台账过渡面</b>（两参构造，EvalRunnerConfig 旧装配）：恢复仍走条件恢复，
 *       所有权判据 = injection 声明的写入值、恢复目标 = 模板 baseline；不落账，
 *       worker 重启清扫不可达——生产接线归 EvalRunnerConfig 传四参构造。</li>
 * </ul>
 */
public final class FlagdScenarioDriver implements ScenarioDriver {

    private static final Logger log = LoggerFactory.getLogger(FlagdScenarioDriver.class);

    private final FlagAdminClient client;
    private final AlertProbe alertProbe;
    private final FlagdRestoreLedger restoreLedger;
    private final Clock clock;

    /** 无台账过渡装配（DR-05 接线前）：不落账、重启清扫不可达；条件恢复仍生效 */
    public FlagdScenarioDriver(FlagAdminClient client, AlertProbe alertProbe) {
        this(client, alertProbe, FlagdRestoreLedger.noop(), Clock.systemUTC());
    }

    public FlagdScenarioDriver(FlagAdminClient client, AlertProbe alertProbe,
                               FlagdRestoreLedger restoreLedger, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
        this.restoreLedger = Objects.requireNonNull(restoreLedger);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ActivationReceipt activate(GoldenCase golden, int roundNo) {
        GoldenCase.Injection injection = golden.injection();
        if (injection == null) {
            throw new IllegalArgumentException(
                    "flagd 场景缺 injection 参数（flag/variant/baseline_variant）: "
                            + golden.scenarioId());
        }
        String flag = injection.flag();
        // DR-05 §7.4：写入前先读实际当前值与代际（读不到如实记 null，不编造原值）
        FlagdState before = tryRead(flag);
        String applied = client.setDefaultVariant(flag, injection.variant());
        // 尽力捕获本次写入的代际令牌（条件恢复的他者改写判据；读不到 = 退化纯值比对）
        FlagdState after = tryRead(flag);
        Instant now = clock.instant();
        restoreLedger.recordActivation(new FlagdRestoreRecord(UUID.randomUUID(), flag,
                golden.scenarioId(), roundNo,
                before == null ? null : before.variant(),
                before == null ? null : before.generation(),
                applied, after == null ? null : after.generation(),
                injection.baselineVariant(), deadlineAt(golden, now),
                FlagdRestoreRecord.State.OPEN, null, now, now));
        return new ActivationReceipt(golden.scenarioId(),
                ChaosAdminClient.actionDigest("flagd", golden.scenarioId(),
                        injection.flag(), injection.variant(), applied).value(),
                0L, alertIdentity(golden));
    }

    @Override
    public RecoveryReceipt deactivate(GoldenCase golden, ActivationReceipt receipt) {
        GoldenCase.Injection injection = Objects.requireNonNull(golden.injection(),
                "flagd 场景缺 injection 参数");
        List<String> unmet = new ArrayList<>();
        Optional<FlagdRestoreRecord> record = restoreLedger
                .findRestorableByFlag(injection.flag())
                .filter(r -> r.scenarioId().equals(golden.scenarioId()));
        // 所有权判据与恢复目标：有台账用实际原值/写入代际；无台账退化 injection 声明面
        String expectedVariant = record.map(FlagdRestoreRecord::appliedVariant)
                .orElse(injection.variant());
        String expectedGeneration = record.map(FlagdRestoreRecord::appliedGeneration)
                .orElse(null);
        String restoreTarget = record.map(FlagdRestoreRecord::restoreTarget)
                .orElse(injection.baselineVariant());
        FlagdConditionalRestore.Result restore = FlagdConditionalRestore.attempt(
                client.asAdminPort(), injection.flag(), expectedVariant,
                expectedGeneration, restoreTarget);
        switch (restore.outcome()) {
            case RESTORED -> record.ifPresent(r -> restoreLedger.close(r.id(),
                    FlagdRestoreRecord.State.RESTORED, restore.detail(), clock.instant()));
            case CONFLICT -> {
                record.ifPresent(r -> restoreLedger.close(r.id(),
                        FlagdRestoreRecord.State.CONFLICT, restore.detail(),
                        clock.instant()));
                unmet.add("flag_restore_conflict:" + restore.detail());
                log.warn("flagd 条件恢复冲突：flag={} {}——不覆盖他者改写，不宣称恢复成功",
                        injection.flag(), restore.detail());
            }
            case UNKNOWN, NOT_APPLIED -> {
                // 读不到/写回失败：不盲写，台账保持可恢复面（UNKNOWN），超 deadline
                // 由 FlagdRestoreSweeper 重试（worker 重启清扫）
                record.ifPresent(r -> restoreLedger.close(r.id(),
                        FlagdRestoreRecord.State.UNKNOWN, restore.detail(),
                        clock.instant()));
                unmet.add(restore.outcome() == FlagdConditionalRestore.Outcome.UNKNOWN
                        ? "flag_state_unknown:" + restore.detail()
                        : "flag_not_restored:" + restore.detail());
            }
        }
        boolean resolved = alertProbe.awaitAllResolved(golden.scenarioId(),
                golden.timing().maxResolvedWaitSeconds());
        if (!resolved) {
            unmet.add("alerts_still_firing");
        }
        return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                receipt.generation(), unmet.isEmpty(), resolved, List.copyOf(unmet));
    }

    /** 作业级截止 = 激活时刻 + 模板全窗口（preheat+hold+firingWait+resolvedWait+cleanup） */
    private static Instant deadlineAt(GoldenCase golden, Instant now) {
        GoldenCase.Timing t = golden.timing();
        return now.plusSeconds((long) t.preheatSeconds() + t.holdSeconds()
                + t.maxFiringWaitSeconds() + t.maxResolvedWaitSeconds()
                + t.cleanupTimeoutSeconds());
    }

    /** 读当前值与代际（尽力面：传输失败 = null，由调用方如实记缺，不阻断激活写入） */
    private FlagdState tryRead(String flag) {
        try {
            return client.readDefaultVariant(flag);
        } catch (RuntimeException e) {
            log.warn("flagd 读当前值失败（如实记缺，不编造）: flag={} {}", flag,
                    e.getMessage());
            return null;
        }
    }

    private static String alertIdentity(GoldenCase golden) {
        return ChaosAdminClient.actionDigest("alert-identity", golden.scenarioId(),
                golden.expectedSymptomCodes()).value();
    }

    /** flagd 变更客户端（窄接口；HTTP 默认实现 POST /flags {flag, variant} 写、
     *  GET /flags/{flag} 读） */
    public interface FlagAdminClient {

        /** 设 defaultVariant，返回实际生效值（服务端回执；BA-19 单键语义） */
        String setDefaultVariant(String flag, String variant);

        /** DR-05 §7.4：读实际当前值与代际令牌（条件恢复判定面；传输错误即抛，
         *  调用方三态化；服务端无代际面 = generation null，退化纯值比对） */
        FlagdState readDefaultVariant(String flag);

        /** DR-05：drill 域恢复窄面适配（driver 条件恢复与 sweeper 截止清扫共用传输面） */
        default FlagdAdminPort asAdminPort() {
            FlagAdminClient self = this;
            return new FlagdAdminPort() {
                @Override
                public FlagdState read(String flag) {
                    return self.readDefaultVariant(flag);
                }

                @Override
                public String write(String flag, String variant) {
                    return self.setDefaultVariant(flag, variant);
                }
            };
        }

        /** 默认 HTTP 实现（token 可空：flag-setter 是否鉴权归部署面） */
        final class Http implements FlagAdminClient {

            private final RestClient rest;

            public Http(String baseUrl) {
                this.rest = RestClient.builder().baseUrl(
                        Objects.requireNonNull(baseUrl)).build();
            }

            @Override
            public String setDefaultVariant(String flag, String variant) {
                Map<?, ?> r = rest.post().uri("/flags")
                        .body(Map.of("flag", flag, "variant", variant))
                        .retrieve().body(Map.class);
                return r == null || r.get("applied") == null ? "" : r.get("applied").toString();
            }

            @Override
            public FlagdState readDefaultVariant(String flag) {
                Map<?, ?> r = rest.get().uri("/flags/{flag}", flag)
                        .retrieve().body(Map.class);
                if (r == null || r.get("variant") == null) {
                    return new FlagdState(null, null);
                }
                Object generation = r.get("generation");
                return new FlagdState(r.get("variant").toString(),
                        generation == null ? null : generation.toString());
            }
        }
    }
}

package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.shared.Digest;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * flagd 场景驱动（M3-17，S1/S2）：flagd defaultVariant 精确切换（BA-19 纪律：
 * 只许目标 flag 的 defaultVariant 单键变更，禁批量替换）。
 *
 * <p>传输面经 {@link FlagAdminClient}（HTTP：POST {flag, variant} 到 eval-mgmt 侧
 * flag-setter；AM0 demo 的 flagd 文件卷/flagd-ui 归部署面，195 门落实端点）。
 * 恢复判定<b>不复用</b> {@code *_current==0}（M3-17 冻结）：归位 baseline variant +
 * checkout 告警 resolved + 烧损率回落（restore_probe 归 AlertProbe PromQL 面）。
 */
public final class FlagdScenarioDriver implements ScenarioDriver {

    private final FlagAdminClient client;
    private final AlertProbe alertProbe;

    public FlagdScenarioDriver(FlagAdminClient client, AlertProbe alertProbe) {
        this.client = Objects.requireNonNull(client);
        this.alertProbe = Objects.requireNonNull(alertProbe);
    }

    @Override
    public ActivationReceipt activate(GoldenCase golden) {
        GoldenCase.Injection injection = golden.injection();
        if (injection == null) {
            throw new IllegalArgumentException(
                    "flagd 场景缺 injection 参数（flag/variant/baseline_variant）: "
                            + golden.scenarioId());
        }
        String applied = client.setDefaultVariant(injection.flag(), injection.variant());
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
        String applied = client.setDefaultVariant(injection.flag(),
                injection.baselineVariant());
        if (!injection.baselineVariant().equals(applied)) {
            unmet.add("flag_not_restored:" + applied);
        }
        boolean resolved = alertProbe.awaitAllResolved(golden.scenarioId(),
                golden.timing().maxResolvedWaitSeconds());
        if (!resolved) {
            unmet.add("alerts_still_firing");
        }
        return new RecoveryReceipt(golden.scenarioId(), receipt.actionDigest(),
                receipt.generation(), unmet.isEmpty(), resolved, List.copyOf(unmet));
    }

    private static String alertIdentity(GoldenCase golden) {
        return ChaosAdminClient.actionDigest("alert-identity", golden.scenarioId(),
                golden.expectedSymptomCodes()).value();
    }

    /** flagd 变更客户端（窄接口；HTTP 默认实现 POST /flags {flag, variant}） */
    public interface FlagAdminClient {

        /** 设 defaultVariant，返回实际生效值（服务端回执；BA-19 单键语义） */
        String setDefaultVariant(String flag, String variant);

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
        }
    }
}

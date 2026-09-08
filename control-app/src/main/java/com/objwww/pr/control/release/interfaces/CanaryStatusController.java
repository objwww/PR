package com.objwww.pr.control.release.interfaces;

import com.objwww.pr.control.release.domain.model.ConfigBundle;
import com.objwww.pr.control.release.domain.repository.CanaryDecisionLogRepository;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository;
import com.objwww.pr.control.release.domain.repository.CanaryWindowVerdictRepository.VerdictRow;
import com.objwww.pr.control.release.domain.repository.ConfigBundleRepository;
import com.objwww.pr.shared.Digest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canary 放量只读观察面（M6-01 落点 9；C-64 登记：拆解无此编号，参照 O-2 先例
 * 作为 M6-01 验收观察面落码，G1 评审追认）。GET /api/canary/status 零写路径——
 * 能力就绪/缺件（NativeCapabilityProbe 投影，C-67 无热开关）、active bundle 的
 * canary 段（percent/上限/白名单规模）、NATIVE 实跑决策计数（爆炸半径重数源）、
 * 可选窗判定序列（rolloutId+candidateDigest 双参）。
 *
 * <p>RBAC 现状（O-4）：沿 ConfigBundleController 惯例——静态 bearer
 * （{@code app.release.api.bearer}）+ 常量时间比较；零仓储触达返回 401。
 */
@RestController
@Profile("docker")
public class CanaryStatusController {

    /** 能力快照（装配时定格；interfaces 不触 infrastructure 探针类型——分层缝） */
    public record Capability(boolean ready, List<String> missing, String capabilityDigestHex) {
    }

    private final ConfigBundleRepository bundles;
    private final CanaryDecisionLogRepository decisions;
    private final CanaryWindowVerdictRepository windows;
    private final Capability capability;
    private final byte[] expectedBearer;

    public CanaryStatusController(ConfigBundleRepository bundles,
                                  CanaryDecisionLogRepository decisions,
                                  CanaryWindowVerdictRepository windows,
                                  Capability capability,
                                  @Value("${app.release.api.bearer}") String bearerToken) {
        this.bundles = bundles;
        this.decisions = decisions;
        this.windows = windows;
        this.capability = capability;
        this.expectedBearer = (bearerToken == null ? "" : bearerToken)
                .getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(path = "/api/canary/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "rolloutId", required = false) String rolloutId,
            @RequestParam(value = "candidateDigest", required = false) String candidateDigest) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nativeReady", capability.ready());
        if (!capability.missing().isEmpty()) {
            body.put("missing", capability.missing());
        }
        if (capability.capabilityDigestHex() != null) {
            body.put("capabilityDigest", capability.capabilityDigestHex());
        }

        body.putAll(bundles.activeDigest()
                .<Map<String, Object>>map(digest -> Map.of(
                        "activeBundleDigest", digest.hex(),
                        "canary", canaryView(digest)))
                .orElseGet(Map::of));
        body.put("nativeDecisions", decisions.countNativeDecisions());

        if (rolloutId != null || candidateDigest != null) {
            if (rolloutId == null || candidateDigest == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "rolloutId 与 candidateDigest 必须成对"));
            }
            body.put("windows", windowView(UUID.fromString(rolloutId), candidateDigest));
        }
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------ 内部

    /** active bundle 的 canary 段投影（段缺失 = 放量未启用，view 为 null） */
    private Map<String, Object> canaryView(Digest digest) {
        ConfigBundle bundle = bundles.findByDigest(digest).orElseThrow();
        if (!(bundle.content().get("canary") instanceof Map<?, ?> canary)) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("percent", canary.get("percent") instanceof Number n ? n.intValue() : null);
        view.put("maxNativeRuns", canary.get("max_native_runs") instanceof Number n
                ? n.intValue() : null);
        view.put("whitelistSize", canary.get("whitelist") instanceof List<?> list
                ? list.size() : 0);
        return view;
    }

    private List<Map<String, Object>> windowView(UUID rolloutId, String candidateDigest) {
        return windows.findByRollout(rolloutId, candidateDigest).stream()
                .map(this::project)
                .toList();
    }

    private Map<String, Object> project(VerdictRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("windowSeq", row.windowSeq());
        view.put("evidenceClass", row.evidenceClass());
        view.put("verdict", row.verdict());
        view.put("eligibleIncidents", row.eligibleIncidents());
        view.put("criticalPass", row.criticalPass());
        view.put("windowStart", row.windowStart() == null ? null : row.windowStart().toString());
        view.put("windowEnd", row.windowEnd() == null ? null : row.windowEnd().toString());
        return view;
    }

    /** 常量时间比较（Bearer 验签；ConfigBundleController 同构） */
    private boolean authorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        byte[] provided = authorizationHeader.substring("Bearer ".length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expectedBearer);
    }
}

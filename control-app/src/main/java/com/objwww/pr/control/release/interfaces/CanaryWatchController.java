package com.objwww.pr.control.release.interfaces;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 金丝雀放量的 metric analysis（业界对齐 Argo Rollouts 分析期，保守实现）：
 * 激活时间之后发起的调查为金丝雀群，实时计算终态失败率；失败率≥50% 且终态≥4 次
 * → suggest=true（产出回滚建议）。**执行仍走既有 RELEASE 回滚命令与资格门**——
 * 本端点只做观测与建议，不做无人自动回滚（不无人变更纪律）。
 * 阈值固定不配置化：本先是保守观测面，阈值放宽属后续独立变更。
 */
@RestController
@RequestMapping("/api/v1/config-bundles")
public class CanaryWatchController {

    private static final int MIN_TERMINAL = 4;
    private static final long FAIL_RATE_NUM = 1;
    private static final long FAIL_RATE_DEN = 2;

    private final JdbcClient jdbc;

    public CanaryWatchController(ObjectProvider<JdbcClient> jdbc) {
        this.jdbc = jdbc.getIfAvailable();
    }

    @GetMapping("/canary-watch")
    public Map<String, Object> watch() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (jdbc == null) {
            body.put("status", "UNAVAILABLE");
            body.put("reason", "DB_FACE_NOT_ASSEMBLED");
            return body;
        }
        List<Map<String, Object>> act = jdbc.sql("""
                select bundle_digest, activated_at, activated_by
                  from config_bundle_active order by activated_at desc limit 1
                """)
                .query((rs, i) -> Map.<String, Object>of(
                        "digest", (Object) rs.getString("bundle_digest"),
                        "activatedAt", (Object) rs.getTimestamp("activated_at").toInstant(),
                        "activatedBy", (Object) rs.getString("activated_by")))
                .list();
        if (act.isEmpty()) {
            body.put("status", "OK");
            body.put("active", false);
            return body;
        }
        String digest = (String) act.get(0).get("digest");
        Instant since = (Instant) act.get(0).get("activatedAt");
        Map<String, Object> stats = jdbc.sql("""
                select count(*) filter (where state in ('SUCCEEDED','FAILED','EXPIRED','CANCELLED')) as terminal,
                       count(*) filter (where state in ('FAILED','EXPIRED')) as failed,
                       count(*) filter (where state in ('QUEUED','RUNNING','REPORTING')) as inflight
                  from rca_run where created_at > :since
                """)
                .param("since", Timestamp.from(since))
                .query((rs, i) -> Map.of(
                        "terminal", (Object) rs.getLong("terminal"),
                        "failed", (Object) rs.getLong("failed"),
                        "inflight", (Object) rs.getLong("inflight")))
                .single();
        long terminal = (Long) stats.get("terminal");
        long failed = (Long) stats.get("failed");
        long inflight = (Long) stats.get("inflight");
        boolean suggest = terminal >= MIN_TERMINAL
                && failed * FAIL_RATE_DEN >= terminal * FAIL_RATE_NUM;
        body.put("status", "OK");
        body.put("active", true);
        body.put("digest", digest);
        body.put("activatedAt", since.toString());
        body.put("activatedBy", act.get(0).get("activatedBy"));
        body.put("window", Map.of("terminal", terminal, "failed", failed, "inflight", inflight));
        body.put("threshold", Map.of("minTerminal", MIN_TERMINAL,
                "failRate", FAIL_RATE_NUM + "/" + FAIL_RATE_DEN));
        body.put("suggestRollback", suggest);
        body.put("suggestReason", suggest
                ? "金丝雀窗口失败率超阈值（" + failed + "/" + terminal + " 终态失败）——建议回滚；执行需 RELEASE 角色过资格门"
                : null);
        body.put("checkedAt", Instant.now().toString());
        return body;
    }
}

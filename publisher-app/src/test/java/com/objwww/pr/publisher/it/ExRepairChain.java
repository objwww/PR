package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * EX-23~EX-29 共享夹具（Ex 前缀防撞名）：完整发布一轮（双命令 CONFIRMED + 双资源 PRESENT）
 * 与 "CHECK_RUN 漂移 MISSING → AUTO repair PENDING → Planner 铸单 DISPATCHED" 链。
 * 只组装既有 ItHarness 能力，不含断言逻辑（计数不符直接夹具失败）。
 */
final class ExRepairChain {

    private ExRepairChain() {
    }

    /** 一轮完整发布的产物锚点 */
    record Published(UUID reviewRunId, UUID checkOperationId, UUID reviewOperationId,
                     UUID checkResourceId, UUID reviewResourceId) {
    }

    /** opened → 评审（零 finding）→ CREATE_CHECK/PUBLISH_REVIEW 双 CONFIRMED → 双资源 PRESENT */
    static Published publishPair(ItHarness harness, WireMockServer wiremock,
                                 String deliveryId, long repositoryId, String repo, int pr,
                                 String headSha) {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent(deliveryId, repositoryId, repo, pr, headSha, "opened"),
                ItTarballs.singleFile("src/A.java", "class A {}\n"), "diff");
        harness.modelClient.enqueueContent("[]");
        harness.newWorker("worker-1").runOnce();
        wiremock.stubFor(post(urlEqualTo("/repos/" + repo + "/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1001,\"html_url\":\"http://x/check/1001\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/" + repo + "/pulls/" + pr + "/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2002,\"html_url\":\"http://x/review/2002\"}")));
        harness.newClaimer().runOnce();
        return new Published(run.getId(),
                singleUuid("SELECT operation_id FROM outbox_command WHERE command_type = 'CREATE_CHECK'"),
                singleUuid("SELECT operation_id FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'"),
                singleUuid("SELECT id FROM publication_resource WHERE resource_type = 'CHECK_RUN'"),
                singleUuid("SELECT id FROM publication_resource WHERE resource_type = 'REVIEW'"));
    }

    /**
     * CHECK_RUN 探针空列表 + sanity 读可达 → MISSING + AUTO repair PENDING → Planner 铸单。
     * REVIEW 资源移出巡检面（next_check_at +1 天），保证漂移轮恰处理 1 条。
     *
     * @return repair 命令 operation_id（repair_request.repair_operation_id）
     */
    static UUID driftCheckToMissingAndPlan(ItHarness harness, WireMockServer wiremock,
                                           String repo, String headSha) {
        deferChecks("REVIEW");
        expediteChecks("CHECK_RUN");
        wiremock.stubFor(get(urlEqualTo("/repos/" + repo + "/commits/" + headSha
                        + "/check-runs?per_page=100&page=1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"check_runs\":[]}")));
        wiremock.stubFor(get(urlEqualTo("/repos/" + repo))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"" + repo + "\"}")));
        if (harness.newDriftReconciler().runOnce() != 1) {
            throw new IllegalStateException("夹具漂移轮应恰处理 1 条 CHECK_RUN 资源");
        }
        if (harness.newRepairPlanner().runOnce() != 1) {
            throw new IllegalStateException("夹具 Planner 应恰铸 1 条 repair 命令");
        }
        return singleUuid("SELECT repair_operation_id FROM repair_request");
    }

    /** 测试动作：指定资源类型移出巡检面（next_check_at 拨到明天） */
    static void deferChecks(String resourceType) {
        PostgresITBase.adminJdbc.sql(
                        "UPDATE publication_resource SET next_check_at = now() + interval '1 day'"
                                + " WHERE resource_type = :type")
                .param("type", resourceType).update();
    }

    /** 测试动作：指定资源类型立即到期（next_check_at 拨回一秒前） */
    static void expediteChecks(String resourceType) {
        PostgresITBase.adminJdbc.sql(
                        "UPDATE publication_resource SET next_check_at = now() - interval '1 second'"
                                + " WHERE resource_type = :type")
                .param("type", resourceType).update();
    }

    /**
     * 零 finding review 的期望正文格式（§4.4 buildBody：人读摘要 + 行尾隐藏 marker）。
     * 测试侧独立书写，不复用 Handler 代码；格式漂移由 EX-23 的真实发布回流对拍暴露。
     */
    static String zeroFindingsReviewBody(UUID reviewOperationId) {
        return "AI Code Review\n\n共 0 个发现：\n\n<!-- ai-review:" + reviewOperationId + " -->";
    }

    /** LIST_REVIEWS 探针 200 响应体（Jackson 编码，防手工拼接转义失真）；id 自 2001 递增 */
    static String reviewListJson(String... bodies) {
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            reviews.add(Map.of("id", 2001 + i,
                    "html_url", "http://x/review/" + (2001 + i), "body", bodies[i]));
        }
        try {
            return PostgresITBase.OM.writeValueAsString(reviews);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID singleUuid(String sql) {
        return PostgresITBase.adminJdbc.sql(sql).query(UUID.class).single();
    }
}

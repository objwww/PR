package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ST-06 审核内容正确性：mock 模型返回 3 个 finding（1 个行号/片段锚点错误）→
 * 2 个精确定位、1 个丢弃；review 行号与 stub 收到的一致。
 */
class ST06FindingMappingIT extends PostgresITBase {

    private static final String HEAD_SHA = "feed" + "0".repeat(36);
    private static final String FILE_CONTENT =
            "package a;\n\npublic class A {\n    void f() {\n        int x = 1;\n    }\n}\n";
    // 行号锚点：第 3 行 "public class A {"、第 5 行 "int x = 1;"

    private WireMockServer wiremock;
    private ItHarness harness;

    @BeforeEach
    void setUp() {
        wiremock = new WireMockServer(wireMockConfig().dynamicPort());
        wiremock.start();
        harness = new ItHarness(casDir, wiremock.baseUrl());
    }

    @AfterEach
    void tearDown() {
        wiremock.stop();
    }

    @Test
    void findingsAreRelocatedBySnippetNotModelLineNumbers() {
        ReviewRun run = harness.dispatchOpened(
                ItHarness.prEvent("st06-d1", 2006L, "objwww/mall", 26, HEAD_SHA, "opened"),
                ItTarballs.singleFile("src/A.java", FILE_CONTENT), "diff");

        // 3 条 finding：2 条锚点真实（模型行号故意报错），1 条片段不存在（应丢弃）
        harness.modelClient.enqueueContent("""
                [
                  {"file":"src/A.java","line":99,"existing_code":"int x = 1;",
                   "rule":"magic-number","severity":"MINOR","message":"魔法数字"},
                  {"file":"src/A.java","line":1,"existing_code":"public class A {",
                   "rule":"naming","severity":"INFO","message":"类名过短"},
                  {"file":"src/A.java","line":2,"existing_code":"this snippet does not exist",
                   "rule":"hallucination","severity":"MAJOR","message":"幻觉片段"}
                ]
                """);
        harness.newWorker("worker-1").runOnce();

        // 2 个精确定位（行号由工程映射纠正：5 与 3）、1 个丢弃
        List<String> lineRanges = adminJdbc.sql(
                "SELECT line_start, line_end FROM review_finding ORDER BY line_start")
                .query((rs, n) -> rs.getInt(1) + "-" + rs.getInt(2)).list();
        assertThat(lineRanges).containsExactly("3-3", "5-5");

        // outbox 里的 review payload：findings=2、stats.dropped=1（T2 与 FindingMapper 同源）
        String payloadHash = adminJdbc.sql("""
                SELECT payload_hash FROM outbox_command WHERE command_type = 'PUBLISH_REVIEW'
                """).query(String.class).single();
        Map<String, Object> payload = harness.payloadReader.read(new Digest(payloadHash));
        assertThat((List<?>) payload.get("findings")).hasSize(2);
        assertThat(((Map<?, ?>) payload.get("stats")).get("dropped")).isEqualTo(1);
        assertThat(((Map<?, ?>) payload.get("stats")).get("findings")).isEqualTo(2);

        // publisher 发布：review body 行号与映射结果一致（stub 收到的即定位后的）
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":1,\"html_url\":\"http://x/1\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/pulls/26/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":2,\"html_url\":\"http://x/2\"}")));
        harness.newClaimer().runOnce();
        wiremock.verify(postRequestedFor(urlEqualTo("/repos/objwww/mall/pulls/26/reviews"))
                .withRequestBody(containing("行 3"))
                .withRequestBody(containing("行 5")));
        assertThat(run).isNotNull();
    }
}

package com.objwww.pr.publisher.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-05 Token 泄漏系统性断言（评审对账缺口）：跑一条完整发布链（假 broker 铸币 → 真
 * GitHubWriteAdapter 写 stub → 两条命令 CONFIRMED），然后扫描 DB 里所有可能被污染的
 * 文本面——execution_event.payload::text、outbox_command 全行文本、publication_resource
 * 全行文本——断言三类机密一律不出现：
 * <ul>
 *   <li>installation token 原文（{@link ItHarness#IT_TOKEN}，铸币返回值）；</li>
 *   <li>App JWT（base64url 的 {@code {"} 前缀即 "eyJ"）；</li>
 *   <li>PEM 私钥标记（"BEGIN ... PRIVATE KEY"——contains "PRIVATE KEY" 是更强的超集判定）。</li>
 * </ul>
 * token 的去处只允许是出站请求的 Authorization 头，落库列里出现任何一个即测试失败。
 */
class SEC05TokenLeakSweepIT extends PostgresITBase {

    private static final String HEAD_SHA = "head" + "c".repeat(36);
    private static final String FILE = "src/B.java";
    private static final String FILE_CONTENT =
            "package b;\n\npublic class B {\n    void g() {\n        t.run();\n    }\n}\n";

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
    void noCredentialMaterialLandsInAnyTable() {
        // 1) 完整发布链：webhook → T0/T1 → Worker（模型桩 1 条 finding）→ outbox 两条命令
        harness.dispatchOpened(
                ItHarness.prEvent("sec05-d1", 2005L, "objwww/mall", 25, HEAD_SHA, "opened"),
                ItTarballs.singleFile(FILE, FILE_CONTENT),
                "diff --git a/src/B.java b/src/B.java\n+        t.run();\n");
        harness.modelClient.enqueueContent("""
                [{"file":"src/B.java","line":1,"existing_code":"t.run();",
                  "rule":"npe-risk","severity":"MAJOR","message":"t 可能为 null"}]
                """);
        harness.newWorker("worker-sec05").runOnce();

        // 2) 铸币（假 broker → IT_TOKEN）→ 写 stub → CONFIRMED
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/check-runs"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":3003,\"html_url\":\"http://github.local/check/3003\"}")));
        wiremock.stubFor(post(urlEqualTo("/repos/objwww/mall/pulls/25/reviews"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":4004,\"html_url\":\"http://github.local/review/4004\"}")));
        harness.newClaimer().runOnce();
        assertThat(adminJdbc.sql("SELECT state FROM outbox_command ORDER BY aggregate_sequence")
                .query(String.class).list()).containsExactly("CONFIRMED", "CONFIRMED");

        // 3) 系统性泄漏扫描：三张表所有文本面
        List<String> surfaces = new ArrayList<>();
        surfaces.addAll(adminJdbc.sql("SELECT COALESCE(payload::text, '') FROM execution_event")
                .query(String.class).list());
        surfaces.addAll(adminJdbc.sql("SELECT o::text FROM outbox_command o")
                .query(String.class).list());
        surfaces.addAll(adminJdbc.sql("SELECT r::text FROM publication_resource r")
                .query(String.class).list());
        assertThat(surfaces).isNotEmpty(); // 扫描面非空，否则断言无意义

        for (String text : surfaces) {
            assertThat(text)
                    .doesNotContain(ItHarness.IT_TOKEN) // installation token 原文
                    .doesNotContain("eyJ")              // App JWT（base64url 的 {" 前缀）
                    .doesNotContain("PRIVATE KEY");     // PEM 私钥标记（超集判定）
        }
    }
}

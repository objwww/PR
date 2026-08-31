package com.objwww.pr.control.interfaces.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitHubWebhookParser 的 updated_at 提取（M1-T05，EX-18）：
 * ISO-8601 正常解析；缺失/非法/非文本一律 null（不猜不补，LWW 快筛转权威读）。
 */
class GitHubWebhookParserTest {

    private final GitHubWebhookParser parser = new GitHubWebhookParser();

    private static byte[] payload(String updatedAtFragment) {
        return ("""
                {"action":"opened","number":7,
                 "pull_request":{"state":"open","draft":false,"merged":false,%s
                   "head":{"sha":"headsha123","ref":"feature"},"base":{"sha":"basesha456","ref":"main"}},
                 "repository":{"id":12345,"full_name":"org/repo"},
                 "installation":{"id":987}}
                """).formatted(updatedAtFragment).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void extractsIso8601UpdatedAt() {
        PullRequestEvent event = parser.parsePullRequest(
                payload("\"updated_at\":\"2025-06-01T12:00:00Z\","), "d-1", "opened");

        assertThat(event.updatedAt()).isEqualTo(Instant.parse("2025-06-01T12:00:00Z"));
    }

    @Test
    void missingUpdatedAtYieldsNullNotError() {
        // EX-18：字段缺失不报错不猜——事件照常解析，updatedAt=null 转权威读
        PullRequestEvent event = parser.parsePullRequest(payload(""), "d-1", "opened");

        assertThat(event.updatedAt()).isNull();
    }

    @Test
    void malformedUpdatedAtYieldsNullNotError() {
        // EX-18：非法格式同样 null（载荷其他部分合法，不因此死信整个事件）
        PullRequestEvent event = parser.parsePullRequest(
                payload("\"updated_at\":\"not-a-timestamp\","), "d-1", "opened");

        assertThat(event.updatedAt()).isNull();
    }

    @Test
    void nonTextualUpdatedAtYieldsNull() {
        PullRequestEvent event = parser.parsePullRequest(
                payload("\"updated_at\":12345,"), "d-1", "opened");

        assertThat(event.updatedAt()).isNull();
    }
}

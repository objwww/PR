package com.objwww.pr.control.domain.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T07 验收"mock 模型可注入"的自证：固定响应按序注入，请求留痕可断言。
 */
class MockModelClientTest {

    @Test
    void injectsFixedResponsesInOrder() {
        MockModelClient mock = new MockModelClient()
                .enqueueContent("first")
                .enqueue(new ModelResult("second", new TokenUsage(1, 2, 3), "mock-model"));

        assertEquals("first", mock.complete(new ModelRequest("p1", 100, Duration.ofSeconds(5))).content());
        assertEquals("second", mock.complete(new ModelRequest("p2", 100, Duration.ofSeconds(5))).content());

        assertEquals(2, mock.requests().size());
        assertEquals("p1", mock.requests().get(0).prompt());
    }

    @Test
    void failsWhenCalledMoreThanEnqueued() {
        MockModelClient mock = new MockModelClient().enqueueContent("only");
        mock.complete(new ModelRequest("p", 100, Duration.ofSeconds(5)));
        assertThrows(IllegalStateException.class,
                () -> mock.complete(new ModelRequest("p", 100, Duration.ofSeconds(5))));
    }
}

package com.objwww.pr.shared;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AFT-04 契约：类型化请求只含操作枚举 + 仓库 + 参数，写/读操作不可混用。
 */
class TypedRequestTest {

    @Test
    void writeRequestRejectsReadOperation() {
        assertThrows(IllegalArgumentException.class, () -> new TypedWriteRequest(
                GitHubOperation.LIST_REVIEWS, "o/r", Map.of()));
    }

    @Test
    void readRequestRejectsWriteOperation() {
        assertThrows(IllegalArgumentException.class, () -> new TypedReadRequest(
                GitHubOperation.CREATE_REVIEW, "o/r", Map.of()));
    }

    @Test
    void withPageDerivesPaginatedProbe() {
        TypedReadRequest probe = new TypedReadRequest(
                GitHubOperation.LIST_REVIEWS, "o/r", Map.of("pr_number", 7));
        TypedReadRequest page2 = probe.withPage(2);
        assertEquals(2, page2.parameters().get("page"));
        assertEquals(7, page2.parameters().get("pr_number"));
        // 原对象不可变
        assertTrue(!probe.parameters().containsKey("page"));
    }

    @Test
    void responseBodiesMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class,
                () -> new TypedResponse(200, Map.of("a", 1), java.util.List.of()));
        assertTrue(TypedResponse.ofStatus(500).isServerError());
        assertTrue(!TypedResponse.ofStatus(422).isServerError());
    }
}

package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rca100Adapter（M5-01）fail-closed 双铁律：版本锚定 v1.1 禁浮动 latest；
 * answer key 未授权 → NOT_AVAILABLE_AUTH（E2E-AM5-02：严禁伪造答案或以
 * RCAEval 顶名）。
 */
class Rca100AdapterTest {

    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private static DatasetAdapter.ImportEntry entry(String caseKey) {
        return new DatasetAdapter.ImportEntry(caseKey, "star-family",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey), null);
    }

    private static DatasetAdapter.ImportRequest request(String version) {
        return new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef("rcabench", version,
                        Digest.sha256Of("rca100-manifest-" + version)),
                PartitionClass.VALIDATION, "https://rcabench.example/v1.1", "Apache-2.0",
                "public", List.of(entry("c1")));
    }

    @Test
    void pinnedToV11RejectsFloatingLatestAndUnknownVersions() {
        Rca100Adapter adapter = new Rca100Adapter(true);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.datasetVersion(request("latest"), ID, NOW))
                .withMessageContaining("v1.1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter.importCases(request("v1.2")));
        assertThat(adapter.datasetVersion(request("v1.1"), ID, NOW).version())
                .isEqualTo("v1.1");
    }

    @Test
    void unauthorizedAnswerKeyFailsClosedWithNotAvailableAuth() {
        Rca100Adapter adapter = new Rca100Adapter(false);
        assertThatThrownBy(() -> adapter.datasetVersion(request("v1.1"), ID, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_AVAILABLE_AUTH");
        assertThatThrownBy(() -> adapter.importCases(request("v1.1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_AVAILABLE_AUTH");
    }

    @Test
    void authorizedIsPublicBenchmarkWithPinnedAdapterVersion() {
        Rca100Adapter adapter = new Rca100Adapter(true);
        DatasetVersion v = adapter.datasetVersion(request("v1.1"), ID, NOW);
        assertThat(v.sourceClass()).isEqualTo(SourceClass.PUBLIC_BENCHMARK);
        assertThat(v.source()).isEqualTo("rca100");
        assertThat(v.adapterVersion()).isEqualTo("rca100-v1.1");
        assertThat(v.contentDigest()).isEqualTo(Digest.sha256Of("rca100-manifest-v1.1"));
        assertThat(adapter.importCases(request("v1.1"))).hasSize(1);
    }
}

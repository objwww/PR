package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
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

/**
 * RcaEvalAdapter（M5-01）：RE2/RE3 Parquet 子集转换面——PUBLIC_BENCHMARK
 * 辅助回归身份、适配器版本固定 rcaeval-v1。
 */
class RcaEvalAdapterTest {

    private static DatasetAdapter.ImportEntry entry(String caseKey) {
        return new DatasetAdapter.ImportEntry(caseKey, "re2-family",
                new TypedRootCause("order-service", "duplicate-payment", "idempotency-missing"),
                List.of("PAYMENT_DUPLICATED"), Map.of("raw", caseKey), null);
    }

    @Test
    void publicBenchmarkSubsetConverter() {
        RcaEvalAdapter adapter = new RcaEvalAdapter();
        DatasetAdapter.ImportRequest req = new DatasetAdapter.ImportRequest(
                new DatasetAdapter.DatasetRef("rcaeval-subset", "re2",
                        Digest.sha256Of("rcaeval-manifest")),
                PartitionClass.VALIDATION, "https://rcaeval.example/re2", "MIT", "public",
                List.of(entry("c1")));

        DatasetVersion v = adapter.datasetVersion(req, UUID.randomUUID(),
                Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(v.source()).isEqualTo("rcaeval");
        assertThat(v.name()).isEqualTo("rcaeval-subset");
        assertThat(v.sourceClass()).isEqualTo(SourceClass.PUBLIC_BENCHMARK);
        assertThat(v.adapterVersion()).isEqualTo("rcaeval-v1");

        EvalCaseV1 c = adapter.importCases(req).get(0);
        assertThat(c.caseKey()).isEqualTo("c1");
        assertThat(c.scenarioFamilyId()).isEqualTo("re2-family");
    }
}

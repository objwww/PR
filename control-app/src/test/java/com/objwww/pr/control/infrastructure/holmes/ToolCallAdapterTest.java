package com.objwww.pr.control.infrastructure.holmes;

import com.objwww.pr.control.alert.domain.model.RcaToolCall;
import com.objwww.pr.control.alert.domain.model.ToolCallStatus;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-06：ToolCall 状态枚举映射（大小写不敏感、未知落 null 不猜测）+
 * 栅栏列统一填充（runId/observedGeneration/schemaVersion/payloadDigest）。
 */
class ToolCallAdapterTest {

    private static final UUID RESULT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Digest PAYLOAD = Digest.sha256Of("pkg");

    @Test
    @DisplayName("状态映射穷举：四枚举大小写不敏感；null/blank/未知 → null")
    void statusMappingExhaustive() {
        assertThat(ToolCallAdapter.mapStatus("success")).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(ToolCallAdapter.mapStatus("SUCCESS")).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(ToolCallAdapter.mapStatus("Error")).isEqualTo(ToolCallStatus.ERROR);
        assertThat(ToolCallAdapter.mapStatus("no_data")).isEqualTo(ToolCallStatus.NO_DATA);
        assertThat(ToolCallAdapter.mapStatus("APPROVAL_REQUIRED"))
                .isEqualTo(ToolCallStatus.APPROVAL_REQUIRED);
        assertThat(ToolCallAdapter.mapStatus(null)).isNull();
        assertThat(ToolCallAdapter.mapStatus("  ")).isNull();
        assertThat(ToolCallAdapter.mapStatus("weird_status")).isNull();
    }

    @Test
    @DisplayName("toDomain：线缆形态 → 内部契约，栅栏列来自收尾事务上下文，时间列为空")
    void mapsRawCallToDomainWithFenceColumns() {
        HolmesResponseParser.RawToolCall raw = new HolmesResponseParser.RawToolCall(
                "call-1", 2, "prometheus_query", "success",
                Digest.sha256Of("params"), Digest.sha256Of("result"));

        RcaToolCall domain = ToolCallAdapter.toDomain(raw, RESULT_ID, RUN_ID, 3, 2, PAYLOAD);

        assertThat(domain.investigationResultId()).isEqualTo(RESULT_ID);
        assertThat(domain.toolCallId()).isEqualTo("call-1");
        assertThat(domain.sequenceNo()).isEqualTo(2);
        assertThat(domain.toolName()).isEqualTo("prometheus_query");
        assertThat(domain.status()).isEqualTo(ToolCallStatus.SUCCESS);
        assertThat(domain.paramsDigest()).isEqualTo(Digest.sha256Of("params"));
        assertThat(domain.resultDigest()).isEqualTo(Digest.sha256Of("result"));
        assertThat(domain.startedAt()).isNull();
        assertThat(domain.finishedAt()).isNull();
        // FUT-50 栅栏直挂列
        assertThat(domain.runId()).isEqualTo(RUN_ID);
        assertThat(domain.observedGeneration()).isEqualTo(3);
        assertThat(domain.schemaVersion()).isEqualTo(2);
        assertThat(domain.payloadDigest()).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("批量映射：未知状态同样落 null，不因单条脏数据丢整批")
    void batchMappingKeepsUnknownStatusEntries() {
        List<HolmesResponseParser.RawToolCall> raw = List.of(
                new HolmesResponseParser.RawToolCall("c1", 1, "t1", "no_data", null, null),
                new HolmesResponseParser.RawToolCall("c2", 2, "t2", "bogus", null, null));

        List<RcaToolCall> mapped = ToolCallAdapter.toDomain(raw, RESULT_ID, RUN_ID, 0, 1, null);

        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).status()).isEqualTo(ToolCallStatus.NO_DATA);
        assertThat(mapped.get(1).status()).isNull();
    }
}

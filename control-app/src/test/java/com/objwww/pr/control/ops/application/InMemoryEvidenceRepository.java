package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EvidenceRepository 测试夹具（A1：按 runId 分组返回，保插入序即仓储序）。
 */
public final class InMemoryEvidenceRepository implements EvidenceRepository {

    private final List<EvidenceEnvelope> rows = new ArrayList<>();

    public void add(EvidenceEnvelope envelope) {
        rows.add(envelope);
    }

    @Override
    public void insert(EvidenceEnvelope envelope) {
        rows.add(envelope);
    }

    @Override
    public Optional<EvidenceEnvelope> findById(UUID evidenceId) {
        return rows.stream().filter(e -> e.evidenceId().equals(evidenceId)).findFirst();
    }

    @Override
    public List<EvidenceEnvelope> findByRunId(UUID runId) {
        return rows.stream().filter(e -> e.runId().equals(runId)).toList();
    }
}

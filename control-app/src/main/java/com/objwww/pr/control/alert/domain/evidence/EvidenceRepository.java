package com.objwww.pr.control.alert.domain.evidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 证据仓储端口（AM4 M4-19，V16 rca_evidence）。读路径必须对存储字节重算比对
 * （五步第④步，篡改显式拒绝）；写路径内置代际栅栏（observed_generation 必须等于
 * run 当前代——同 schema_version 不同 generation 在准入面拒绝）。
 */
public interface EvidenceRepository {

    /** 写入（canonical 字节原样落库）；run 缺行/跨代抛 IllegalStateException */
    void insert(EvidenceEnvelope envelope);

    /** 读出并 verify（篡改抛 IllegalStateException） */
    Optional<EvidenceEnvelope> findById(UUID evidenceId);

    /** run 全量证据（verify 后按 created_at, id 序） */
    List<EvidenceEnvelope> findByRunId(UUID runId);
}

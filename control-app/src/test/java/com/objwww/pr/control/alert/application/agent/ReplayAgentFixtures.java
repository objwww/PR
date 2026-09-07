package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** M4-28/29 回放 Agent 测试共享夹具（进程内，无网络） */
final class ReplayAgentFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplayAgentFixtures() {
    }

    static ToolGateway gateway(ToolRegistry registry, Set<String> allowedTools) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        return new ToolGateway(registry, new ToolPolicy(allowedTools),
                pool, java.time.Clock.systemUTC(), null);
    }

    static byte[] fixture(String path) {
        try (InputStream in = ReplayAgentFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("fixture 缺失: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 账本内存件：open/终态 CAS 语义与 V15 一致 */
    static final class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, String toolName, ToolInvocationState state,
                ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.toolName(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(id, row.toolName(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件 */
    static final class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();
        final MemLedger ledger = new MemLedger();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }
}

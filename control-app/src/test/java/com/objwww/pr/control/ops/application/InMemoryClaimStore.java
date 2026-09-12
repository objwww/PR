package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.claim.ClaimIdentity;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ClaimStore 测试夹具（A1：只支持读面 findByRunId；写路径本测试面不消费）。
 */
public final class InMemoryClaimStore implements ClaimStore {

    private final List<ClaimRow> rows = new ArrayList<>();

    public void add(ClaimRow row) {
        rows.add(row);
    }

    @Override
    public ClaimAppendResult append(UUID runId, ClaimVerdict verdict) {
        throw new UnsupportedOperationException("read-face fixture");
    }

    @Override
    public long markUnresolved(UUID runId, ClaimIdentity identity, String policyVersion) {
        throw new UnsupportedOperationException("read-face fixture");
    }

    @Override
    public List<ClaimRow> findByRunId(UUID runId) {
        return rows.stream().filter(r -> r.runId().equals(runId)).toList();
    }
}

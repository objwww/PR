package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.ops.domain.model.OperatorCase;
import com.objwww.pr.control.ops.domain.repository.OperatorCaseRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * OperatorCase 仓储测试夹具（单线程 CAS fake：update 以 revision 相等为准；
 * lock 即直接读——并发恰一成功的真语义由 PostgresOperatorCaseIT 兜底）。
 */
public final class InMemoryOperatorCases implements OperatorCaseRepository {

    private final Map<UUID, OperatorCase> rows = new HashMap<>();

    public OperatorCase byId(UUID id) {
        return Optional.ofNullable(rows.get(id)).orElseThrow();
    }

    public int size() {
        return rows.size();
    }

    @Override
    public void insert(OperatorCase operatorCase) {
        rows.put(operatorCase.id(), operatorCase);
    }

    @Override
    public Optional<OperatorCase> lockByTenantAndFingerprint(String tenant, String fingerprint) {
        return rows.values().stream()
                .filter(c -> c.tenant().equals(tenant) && c.fingerprint().equals(fingerprint))
                .findFirst();
    }

    @Override
    public Optional<OperatorCase> findById(UUID id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<OperatorCase> findAll() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public boolean update(OperatorCase operatorCase, long expectedRevision) {
        OperatorCase current = rows.get(operatorCase.id());
        if (current == null || current.revision() != expectedRevision) {
            return false;
        }
        rows.put(operatorCase.id(), operatorCase);
        return true;
    }
}

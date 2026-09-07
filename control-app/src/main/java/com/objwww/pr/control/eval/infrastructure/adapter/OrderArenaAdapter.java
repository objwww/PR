package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OrderArena 适配器（M5-01）：订单域私有集（fault-injection 故障注入 +
 * 脱敏生产回归，scenario 来源见 GoldenScenarioRegistry/eval-scenarios.yml），
 * 唯一决定上线的主质量门（source_class=PRIVATE）。
 */
public class OrderArenaAdapter implements DatasetAdapter {

    @Override
    public DatasetVersion datasetVersion(ImportRequest request, UUID id, Instant importedAt) {
        return DatasetAdapter.version(request, id, importedAt,
                "order-arena", SourceClass.PRIVATE, request.partitionClass(), "oa-v1",
                DatasetVersion.familyDigest(importCases(request)));
    }

    @Override
    public List<EvalCaseV1> importCases(ImportRequest request) {
        return DatasetAdapter.cases(request);
    }
}

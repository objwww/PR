package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RcaEval 适配器（M5-01）：RCAEval RE2/RE3 子集（辅助回归；只按 Case 下载
 * Parquet 子集，不在 2C4G 解压全量——全量子集化归 runner 面职责）。
 */
public class RcaEvalAdapter implements DatasetAdapter {

    @Override
    public DatasetVersion datasetVersion(ImportRequest request, UUID id, Instant importedAt) {
        return DatasetAdapter.version(request, id, importedAt,
                "rcaeval", SourceClass.PUBLIC_BENCHMARK, request.partitionClass(), "rcaeval-v1",
                DatasetVersion.familyDigest(importCases(request)));
    }

    @Override
    public List<EvalCaseV1> importCases(ImportRequest request) {
        return DatasetAdapter.cases(request);
    }
}

package com.objwww.pr.control.eval.infrastructure.adapter;

import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.port.DatasetAdapter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rca100 适配器（M5-01）：阿里云 STAROps RCA-Bench，外部一致性辅助门
 * （source_class=PUBLIC_BENCHMARK，不单独决定上线、不冒充私有 HOLDOUT）。
 *
 * <p>两条 fail-closed 铁律：① 版本锚定 {@link #PINNED_VERSION}，非锚定版本声明
 * （含浮动 latest）直接拒绝；② answer key 授权核查前置——未授权时 dataset 行与
 * cases 全部 NOT_AVAILABLE_AUTH（E2E-AM5-02：严禁伪造答案或以 RCAEval 顶名）。
 */
public class Rca100Adapter implements DatasetAdapter {

    public static final String PINNED_VERSION = "v1.1";

    private final boolean answerKeyAuthorized;

    public Rca100Adapter(boolean answerKeyAuthorized) {
        this.answerKeyAuthorized = answerKeyAuthorized;
    }

    @Override
    public DatasetVersion datasetVersion(ImportRequest request, UUID id, Instant importedAt) {
        requirePinnedAndAuthorized(request);
        return DatasetAdapter.version(request, id, importedAt,
                "rca100", SourceClass.PUBLIC_BENCHMARK, request.partitionClass(),
                "rca100-" + PINNED_VERSION,
                DatasetVersion.familyDigest(importCases(request)));
    }

    @Override
    public List<EvalCaseV1> importCases(ImportRequest request) {
        requirePinnedAndAuthorized(request);
        return DatasetAdapter.cases(request);
    }

    private void requirePinnedAndAuthorized(ImportRequest request) {
        if (!answerKeyAuthorized) {
            throw new IllegalStateException("NOT_AVAILABLE_AUTH: RCA-100 answer key 未获授权"
                    + "（须联系维护方完成 Redistribution 授权核查），fail-closed 拒绝导入");
        }
        if (!PINNED_VERSION.equals(request.datasetRef().version())) {
            throw new IllegalArgumentException("RCA-100 版本须锚定 " + PINNED_VERSION
                    + "（禁浮动 latest）: " + request.datasetRef().version());
        }
    }
}

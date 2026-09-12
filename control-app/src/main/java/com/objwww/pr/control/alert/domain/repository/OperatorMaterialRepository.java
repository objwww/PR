package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.OperatorMaterial;

import java.util.List;
import java.util.UUID;

/**
 * 人工材料台账端口（MC31/32，V96）。append-only：insert 唯一键冲突显式抛
 * （uq(incident_id, revision) CAS 串行化点的调用方竞态兜底依据）。
 */
public interface OperatorMaterialRepository {

    void insert(OperatorMaterial material);

    /** 按 revision 升序全量（台账/详情面） */
    List<OperatorMaterial> findByIncident(UUID incidentId);

    /** 材料集当前版本（无行=0；CAS 的 current 面） */
    int currentRevision(UUID incidentId);

    /** 合并面：某调查 run 关联的已准入材料（信封 operator_materials 槽唯一来源） */
    List<OperatorMaterial> findAcceptedByRun(UUID runId);
}

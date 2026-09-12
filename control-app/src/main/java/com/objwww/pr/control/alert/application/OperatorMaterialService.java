package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.OperatorMaterial;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 人工补充材料准入（MC31/32，MA-07 人为补充材料审计面）——行锁串行化的 CAS 面：
 * <ol>
 *   <li><b>身份面（MC31）</b>：operator 由调用方从认证主体取（AuthenticatedActor，
 *       非请求体自报）；</li>
 *   <li><b>CAS（MC32）</b>：incident 行锁下复核材料集版本——baseRevision 与当前
 *       不一致 → CONFLICT（不落行，应答携带当前版本可见）；一致 → ACCEPTED 行
 *       revision=base+1（uq(incident_id,revision) 唯一键兜底并发路径）——两人同
 *       revision 提交不同处置至多一个生效；</li>
 *   <li><b>终态围栏（MC32）</b>：incident 已 RESOLVED → REJECTED_LATE 审计行
 *       （明确终态，不无限挂起也不静默丢弃——材料集版本照常单调推进）；</li>
 *   <li><b>准入面不变（MC31）</b>：材料不入 validRefs（X5/JUDGMENT 无证判断不能
 *       绕过 Claim 准入），信封面单列标注来源与种类。</li>
 * </ol>
 */
public class OperatorMaterialService {

    private static final Logger log = LoggerFactory.getLogger(OperatorMaterialService.class);

    /** 提交（operator 由控制器从认证主体取，不进请求体） */
    public record Submission(UUID incidentId, UUID runId, OperatorMaterial.Kind kind,
            String sourceRef, String content, int baseRevision) {
    }

    /** 受理产物 */
    public record Verdict(OperatorMaterial material, Outcome outcome, int currentRevision) {

        public enum Outcome {ACCEPTED, CONFLICT, LATE}
    }

    private final OperatorMaterialRepository materials;
    private final IncidentRepository incidents;
    private final TransactionOperations tx;
    private final AlertClock clock;

    public OperatorMaterialService(OperatorMaterialRepository materials,
            IncidentRepository incidents, TransactionOperations tx, AlertClock clock) {
        this.materials = Objects.requireNonNull(materials, "materials");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 受理（可任意次重入）。CONFLICT 不落行（提交从未成为材料，应答可见当前版本）；
     * ACCEPTED/REJECTED_LATE 落台账行。
     *
     * @throws IllegalArgumentException incident 不存在
     */
    public Verdict submit(Submission submission, String operator) {
        Objects.requireNonNull(operator, "operator（认证端身份）");
        return tx.execute(status -> {
            Incident incident = incidents.findByIdForUpdate(submission.incidentId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "incident 不存在: " + submission.incidentId()));
            Instant now = clock.now();
            int current = materials.currentRevision(incident.id());
            if (incident.status() == com.objwww.pr.control.alert.domain.model
                    .IncidentStatus.RESOLVED) {
                OperatorMaterial late = new OperatorMaterial(UUID.randomUUID(),
                        incident.id(), submission.runId(), operator, submission.kind(),
                        submission.sourceRef(), submission.content(),
                        submission.baseRevision(), current + 1,
                        OperatorMaterial.Admission.REJECTED_LATE, now);
                insert(late);
                log.info("人工材料 REJECTED_LATE incident={} rev={}（终态明确）",
                        incident.id(), late.revision());
                return new Verdict(late, Verdict.Outcome.LATE,
                        materials.currentRevision(incident.id()));
            }
            if (submission.baseRevision() != current) {
                log.info("人工材料 CAS 冲突 incident={} base={} current={}",
                        incident.id(), submission.baseRevision(), current);
                return new Verdict(null, Verdict.Outcome.CONFLICT, current);
            }
            OperatorMaterial accepted = new OperatorMaterial(UUID.randomUUID(),
                    incident.id(), submission.runId(), operator, submission.kind(),
                    submission.sourceRef(), submission.content(),
                    submission.baseRevision(), current + 1,
                    OperatorMaterial.Admission.ACCEPTED, now);
            insert(accepted);
            log.info("人工材料 ACCEPTED incident={} rev={} kind={} operator={}",
                    incident.id(), accepted.revision(), accepted.kind(), operator);
            return new Verdict(accepted, Verdict.Outcome.ACCEPTED,
                    accepted.revision());
        });
    }

    /** 非行锁路径（未来旁路面）撞唯一键 → 以 CONFLICT 语义兜底 */
    private void insert(OperatorMaterial material) {
        try {
            materials.insert(material);
        } catch (DuplicateKeyException raced) {
            throw new IllegalStateException("材料版本唯一键竞态（应被行锁串行化）", raced);
        }
    }
}

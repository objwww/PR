package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.DelegationReceipt;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 子任务回执准入（MC21~23，R7 方案 §20.1"结果按 messageId/动作身份幂等准入；
 * 旧 epoch/已取消/超出当前任务范围的结果仅审计不合入"）——单事务封闭裁决：
 * <ol>
 *   <li><b>幂等短路</b>：同 messageId 已准入 → 原样返回既有行（duplicate=true，
 *       合并面不二次消费，父任务推进不重复）；</li>
 *   <li><b>终态围栏（MC23）</b>：run 已离活跃集 → LATE 审计行（载荷保留审计面，
 *       不合入有效记忆，不改终态——W1 终态后迟到回执不冒充新现场）；</li>
 *   <li><b>身份面</b>：primaryTaskId/gapId/roleId 经 {@code parentRequestId}
 *       （裁决行 id）从既成台账解析（可信身份由 Host 赋予，生产方不自报），且须
 *       对得上 APPROVED 回填的子任务与 run/round 归属；</li>
 *   <li><b>限长（MC21）</b>：裁剪后有界载荷超 {@value #MAX_RECEIPT_BYTES} 字节 →
 *       OVERSIZED 审计行（载荷不落库，digest/字节数留痕），无内存失控；</li>
 *   <li><b>结构契约（§19.5）</b>：FAILED 无缺口清单、四清单全空 → REJECTED_SHAPE
 *       审计行——任务失败也必须返回结构化缺口。</li>
 * </ol>
 * 全部非 ACCEPTED 裁决都落行留审计；行只增不改。生产方挂点 = 子任务终态迁移处
 * （NativeInvestigationExecutor 非主任务 DONE/DEAD 收尾），messageId 由生产方按
 * (childTaskId, attempt) 确定性铸造——同结果重投同键（恢复重驱=重投，恰一次合并），
 * 重试新尝试新键。
 */
public class DelegationReceiptService {

    private static final Logger log = LoggerFactory.getLogger(DelegationReceiptService.class);

    /** 有界载荷上限（字节，UTF-8；V96 ck_mc21_receipt_payload_size 同值背书） */
    public static final int MAX_RECEIPT_BYTES = 64 * 1024;
    /** 每清单项数上限（findings/support/counter/missing 同界） */
    static final int MAX_ITEMS = 100;
    /** 单项字符上限（超界截断留痕，与 ContextAssembler 界一致风格） */
    static final int MAX_ITEM_CHARS = 2000;

    /**
     * 回执提交（生产方面；messageId 必填=幂等键）。primaryTaskId/gapId/roleId 不由
     * 生产方自报——经 {@code parentRequestId}（裁决行 id）从既成台账解析（§20.1
     * 可信身份由 Host 赋予），解析失败=身份面拒绝。
     */
    public record Submission(UUID messageId, UUID runId, UUID parentRequestId,
            UUID childTaskId, int roundId,
            DelegationReceipt.ChildStatus childStatus,
            List<String> findings, List<String> supportRefs,
            List<String> counterRefs, List<String> missingInformation) {

        /** 生产方简形：MC22 反证面由回执消费方（记忆槽）承载，生产侧默认空 */
        public static Submission of(UUID messageId, UUID runId, UUID parentRequestId,
                UUID childTaskId, int roundId, DelegationReceipt.ChildStatus childStatus,
                List<String> findings, List<String> supportRefs,
                List<String> missingInformation) {
            return new Submission(messageId, runId, parentRequestId, childTaskId, roundId,
                    childStatus, findings, supportRefs, List.of(), missingInformation);
        }
    }

    /** 准入产物：台账行 + 是否重复投递（duplicate=true 时合并语义以既有行为准） */
    public record Verdict(DelegationReceipt receipt, boolean duplicate) {

        public boolean merged() {
            return receipt.merged();
        }
    }

    private final DelegationReceiptRepository receipts;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final DelegationDecisionRepository decisions;
    private final TransactionOperations tx;
    private final AlertClock clock;
    private final ObjectMapper mapper;

    public DelegationReceiptService(DelegationReceiptRepository receipts,
            RcaRunRepository runs, RcaTaskRepository tasks,
            DelegationDecisionRepository decisions,
            TransactionOperations tx, AlertClock clock, ObjectMapper mapper) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 幂等准入（可任意次重入）。返回裁决行（含非 ACCEPTED 审计行）；重复投递
     * 返回既有行。准入不抛业务异常（审计面在台账行），仅 run 缺席等编程错误上抛。
     */
    public Verdict submit(Submission submission) {
        Objects.requireNonNull(submission.messageId(), "messageId");
        Optional<DelegationReceipt> existing = receipts.findByMessageId(
                submission.messageId());
        if (existing.isPresent()) {
            return new Verdict(existing.get(), true);
        }
        Verdict verdict = tx.execute(status -> admitOnce(submission));
        return verdict == null ? retryAsDuplicate(submission.messageId()) : verdict;
    }

    private Verdict admitOnce(Submission submission) {
        Instant now = clock.now();
        RcaRun run = runs.findById(submission.runId()).orElse(null);
        if (run == null) {
            throw new IllegalArgumentException(
                    "run 不存在，回执无处落地: " + submission.runId());
        }
        // 身份解析前置：审计行也带既成身份（可空=身份面拒绝行的诚实态）
        DelegationDecision decision = submission.parentRequestId() == null ? null
                : decisions.findById(submission.parentRequestId()).orElse(null);
        UUID primaryTaskId = decision == null ? null : decision.primaryTaskId();
        String gapId = decision == null ? "" : decision.gapId();
        String roleId = decision == null ? "" : decision.roleId();

        // 终态围栏（MC23）：run 离活跃集 → LATE 审计，不合入不改终态
        if (!run.state().isActive()) {
            DelegationReceipt late = auditRow(submission, primaryTaskId, gapId, roleId,
                    DelegationReceipt.Admission.LATE,
                    "run 已终态（state=" + run.state() + "），迟到回执仅审计", now);
            insertAudit(late);
            return new Verdict(late, false);
        }
        // 身份面：对得上既成 APPROVED 裁决与子任务现实（Host 赋予可信身份）
        String shapeProblem = identityProblem(decision, submission);
        if (shapeProblem != null) {
            DelegationReceipt rejected = auditRow(submission, primaryTaskId, gapId,
                    roleId, DelegationReceipt.Admission.REJECTED_SHAPE, shapeProblem,
                    now);
            insertAudit(rejected);
            return new Verdict(rejected, false);
        }
        // MC21 限长先于裁剪：以原始载荷计量（先裁后量 = 超限不可达）。超限 →
        // OVERSIZED 显式拒绝：载荷不落库，digest/原始字节数留痕可对账
        String rawPayload = payloadOf(submission.childStatus(), submission.findings(),
                submission.supportRefs(), submission.counterRefs(),
                submission.missingInformation());
        int rawBytes = rawPayload.getBytes(StandardCharsets.UTF_8).length;
        DelegationReceipt receipt;
        if (rawBytes > MAX_RECEIPT_BYTES) {
            receipt = new DelegationReceipt(UUID.randomUUID(), submission.messageId(),
                    submission.runId(), primaryTaskId, submission.childTaskId(),
                    submission.roundId(), gapId, roleId,
                    submission.childStatus(), DelegationReceipt.Admission.OVERSIZED,
                    List.of(), List.of(), List.of(), List.of(),
                    Digest.sha256Of(rawPayload).value(), rawBytes, now);
        } else {
            // 有界化裁剪（接受才裁：MAX_ITEMS × MAX_ITEM_CHARS 封顶）
            List<String> findings = clipped(submission.findings());
            List<String> supportRefs = clipped(submission.supportRefs());
            List<String> counterRefs = clipped(submission.counterRefs());
            List<String> missingInformation = clipped(submission.missingInformation());
            String payload = payloadOf(submission.childStatus(), findings, supportRefs,
                    counterRefs, missingInformation);
            int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
            if (contractProblem(submission.childStatus(), findings, supportRefs,
                    counterRefs, missingInformation) instanceof String problem) {
                receipt = auditRow(submission, primaryTaskId, gapId, roleId,
                        DelegationReceipt.Admission.REJECTED_SHAPE, problem, now);
            } else {
                receipt = new DelegationReceipt(UUID.randomUUID(),
                        submission.messageId(), submission.runId(), primaryTaskId,
                        submission.childTaskId(), submission.roundId(), gapId, roleId,
                        submission.childStatus(), DelegationReceipt.Admission.ACCEPTED,
                        findings, supportRefs, counterRefs, missingInformation,
                        Digest.sha256Of(payload).value(), bytes, now);
            }
        }
        try {
            receipts.insert(receipt);
        } catch (DuplicateKeyException raced) {
            // 并发同 messageId：以先到准入行为准，本副本并报告不落行（裁决幂等）
            log.info("回执并发撞既成准入行（messageId={}），以先到者为准",
                    submission.messageId());
            return new Verdict(
                    receipts.findByMessageId(submission.messageId()).orElse(receipt),
                    true);
        }
        if (receipt.merged()) {
            log.info("回执准入 ACCEPTED run={} child={} gap={} bytes={}",
                    submission.runId(), submission.childTaskId(), gapId,
                    receipt.payloadBytes());
        } else {
            log.warn("回执准入 {} run={} child={} gap={}", receipt.admission(),
                    submission.runId(), submission.childTaskId(), gapId);
        }
        return new Verdict(receipt, false);
    }

    /** tx 外并发兜底：准入已提交但事务返回前被同键抢行 → 重读既有行 */
    private Verdict retryAsDuplicate(UUID messageId) {
        return new Verdict(receipts.findByMessageId(messageId)
                .orElseThrow(() -> new IllegalStateException(
                        "并发准入后回执行缺席: " + messageId)), true);
    }

    /**
     * 身份面核验（null=通过）：裁决行在案且 APPROVED、回填的 child_task_id 一致、
     * 子任务存在且 run/round 归属与回执声明一致。
     */
    private String identityProblem(DelegationDecision decision, Submission submission) {
        if (decision == null || decision.status() != DelegationDecision.Status.APPROVED
                || !submission.childTaskId().equals(decision.childTaskId())) {
            return "回执对不上既成 APPROVED 裁决（parentRequestId="
                    + submission.parentRequestId() + "）";
        }
        RcaTask child = tasks.findById(submission.childTaskId()).orElse(null);
        if (child == null || !child.runId().equals(submission.runId())
                || child.roundId() != submission.roundId()) {
            return "子任务归属与回执声明不一致（run/round）";
        }
        return null;
    }

    /** 结构契约（§19.5）：FAILED 必带缺口；全空回执 = 无信息结构 */
    private String contractProblem(DelegationReceipt.ChildStatus childStatus,
            List<String> findings, List<String> supportRefs,
            List<String> counterRefs, List<String> missingInformation) {
        if (childStatus == DelegationReceipt.ChildStatus.FAILED
                && missingInformation.isEmpty()) {
            return "FAILED 回执必须带结构化缺口（missing_information 非空）";
        }
        if (findings.isEmpty() && supportRefs.isEmpty() && counterRefs.isEmpty()
                && missingInformation.isEmpty()) {
            return "回执四清单全空（无 findings/引用/反证/缺口），不是结构化结果";
        }
        return null;
    }

    /** 审计行（LATE/REJECTED_SHAPE）：有界载荷原样保留（迟到可追认，审计不丢） */
    private DelegationReceipt auditRow(Submission submission, UUID primaryTaskId,
            String gapId, String roleId, DelegationReceipt.Admission admission,
            String problem, Instant now) {
        List<String> findings = clipped(submission.findings());
        List<String> supportRefs = clipped(submission.supportRefs());
        List<String> counterRefs = clipped(submission.counterRefs());
        List<String> missingInformation = new ArrayList<>(clipped(
                submission.missingInformation()));
        if (problem != null) {
            missingInformation.add(problem);
        }
        String payload = payloadOf(submission.childStatus(), findings, supportRefs,
                counterRefs, missingInformation);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_RECEIPT_BYTES) {
            // 审计行同样不吃无界载荷：超限退化为 digest+字节数留痕
            return new DelegationReceipt(UUID.randomUUID(), submission.messageId(),
                    submission.runId(), primaryTaskId, submission.childTaskId(),
                    submission.roundId(), gapId, roleId, submission.childStatus(),
                    admission, List.of(), List.of(), List.of(),
                    List.of("payload 超限仅留痕: " + problem),
                    Digest.sha256Of(payload).value(), bytes, now);
        }
        return new DelegationReceipt(UUID.randomUUID(), submission.messageId(),
                submission.runId(), primaryTaskId, submission.childTaskId(),
                submission.roundId(), gapId, roleId, submission.childStatus(),
                admission, findings, supportRefs, counterRefs, missingInformation,
                Digest.sha256Of(payload).value(), bytes, now);
    }

    private void insertAudit(DelegationReceipt row) {
        try {
            receipts.insert(row);
        } catch (DuplicateKeyException raced) {
            log.info("审计回执撞既成行（messageId={}），以先到者为准", row.messageId());
        }
    }

    private static List<String> clipped(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : items) {
            if (out.size() >= MAX_ITEMS) {
                break;
            }
            if (item == null || item.isBlank()) {
                continue;
            }
            out.add(item.length() > MAX_ITEM_CHARS
                    ? item.substring(0, MAX_ITEM_CHARS - 1) + "…" : item);
        }
        return List.copyOf(out);
    }

    /** 载荷 = 四清单的规范 JSON（digest 与尺寸的度量对象；清单以裁剪后为准） */
    private String payloadOf(DelegationReceipt.ChildStatus childStatus,
            List<String> findings, List<String> supportRefs,
            List<String> counterRefs, List<String> missingInformation) {
        try {
            return mapper.writeValueAsString(java.util.Map.of(
                    "child_status", childStatus.name(),
                    "findings", findings,
                    "support_refs", supportRefs,
                    "counter_refs", counterRefs,
                    "missing_information", missingInformation));
        } catch (Exception e) {
            throw new IllegalStateException("回执载荷序列化失败", e);
        }
    }
}

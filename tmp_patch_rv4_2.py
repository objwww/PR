# -*- coding: utf-8 -*-
import io

p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/DelegationReceiptService.java'
t = io.open(p, encoding='utf-8').read()

# --- imports + ctor + field：加 EvidenceRepository（可空=legacy 假件面跳过载荷校验）
old = """import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;"""
new = """import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository;
import com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;"""
assert old in t
t = t.replace(old, new)

old = """    private final DelegationReceiptRepository receipts;
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
    }"""
new = """    private final DelegationReceiptRepository receipts;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final DelegationDecisionRepository decisions;
    /** RV04：引用 Host 校验面（可空=假件环境跳过载荷校验）——support/counter 引用
     * 必须解析到本 run 证据行，模型自报反证不直接流入记忆（T22） */
    private final EvidenceRepository evidence;
    private final TransactionOperations tx;
    private final AlertClock clock;
    private final ObjectMapper mapper;

    public DelegationReceiptService(DelegationReceiptRepository receipts,
            RcaRunRepository runs, RcaTaskRepository tasks,
            DelegationDecisionRepository decisions,
            TransactionOperations tx, AlertClock clock, ObjectMapper mapper) {
        this(receipts, runs, tasks, decisions, null, tx, clock, mapper);
    }

    public DelegationReceiptService(DelegationReceiptRepository receipts,
            RcaRunRepository runs, RcaTaskRepository tasks,
            DelegationDecisionRepository decisions, EvidenceRepository evidence,
            TransactionOperations tx, AlertClock clock, ObjectMapper mapper) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.evidence = evidence;
        this.tx = Objects.requireNonNull(tx, "tx");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }"""
assert old in t
t = t.replace(old, new)

# --- admitOnce：线性化点 + 引用校验 + insertIfAbsent
old = """    private Verdict admitOnce(Submission submission) {
        Instant now = clock.now();
        RcaRun run = runs.findById(submission.runId()).orElse(null);"""
new = """    private Verdict admitOnce(Submission submission) {
        Instant now = clock.now();
        // RV04/T21：行锁线性化点——取消（run CAS）与回执准入同一把 run 行锁定序；
        // 取消先提交 → 本围栏判 LATE；准入先提交 → 取消在其后照常，回执不算迟到。
        // 单行锁无交叉，无锁序环。
        RcaRun run = runs.findByIdForUpdate(submission.runId()).orElse(null);"""
assert old in t
t = t.replace(old, new)

old = """        // MC21 限长先于裁剪：以原始载荷计量（先裁后量 = 超限不可达）。超限 →"""
new = """        // RV04/T22：引用 Host 校验（先于限长/契约）——support/counter 引用必须
        // 解析到本 run 证据行；模型自报的越界/伪造引用不能借回执流入记忆
        if (evidence != null) {
            String refProblem = refsProblem(submission);
            if (refProblem != null) {
                DelegationReceipt rejected = auditRow(submission, primaryTaskId, gapId,
                        roleId, DelegationReceipt.Admission.REJECTED_SHAPE, refProblem,
                        now);
                insertAudit(rejected);
                return new Verdict(rejected, false);
            }
        }
        // MC21 限长先于裁剪：以原始载荷计量（先裁后量 = 超限不可达）。超限 →"""
assert old in t
t = t.replace(old, new)

old = """        try {
            receipts.insert(receipt);
        } catch (DuplicateKeyException raced) {
            // 并发同 messageId：以先到准入行为准，本副本并报告不落行（裁决幂等）
            log.info("回执并发撞既成准入行（messageId={}），以先到者为准",
                    submission.messageId());
            return new Verdict(
                    receipts.findByMessageId(submission.messageId()).orElse(receipt),
                    true);
        }"""
new = """        // RV04/T20：ON CONFLICT 原子幂等——PG 事务内唯一冲突会置 aborted（后续
        // 语句 25P02），不能异常后同事务续操作；冲突面走 insertIfAbsent=0 后另条
        // 查询读实际胜者（事务健康，同事务读合法）
        if (receipts.insertIfAbsent(receipt) == 0) {
            log.info("回执并发撞既成准入行（messageId={}），以先到者为准",
                    submission.messageId());
            return new Verdict(
                    receipts.findByMessageId(submission.messageId()).orElse(receipt),
                    true);
        }"""
assert old in t
t = t.replace(old, new)

old = """    private void insertAudit(DelegationReceipt row) {
        try {
            receipts.insert(row);
        } catch (DuplicateKeyException raced) {
            log.info("审计回执撞既成行（messageId={}），以先到者为准", row.messageId());
        }
    }"""
new = """    private void insertAudit(DelegationReceipt row) {
        // RV04：审计行同走 ON CONFLICT 面（同事务不产生 aborted）
        receipts.insertIfAbsent(row);
    }"""
assert old in t
t = t.replace(old, new)

# --- refsProblem helper（放 identityProblem 前）
old = """    /**
     * 身份面核验（null=通过）：裁决行在案且 APPROVED、回填的 child_task_id 一致、"""
new = """    /**
     * RV04/T22 引用 Host 校验（null=通过）：support/counter 引用必须是本 run 已
     * 落库证据行（UUID 形态 + findById 命中 + run 归属一致）；越界清单随审计行留痕。
     */
    private String refsProblem(Submission submission) {
        List<String> offenders = new ArrayList<>();
        for (List<String> bucket : List.of(submission.supportRefs(),
                submission.counterRefs())) {
            for (String ref : bucket) {
                if (offenders.size() >= 5) {
                    break;
                }
                if (!isRunEvidence(ref, submission.runId())) {
                    offenders.add(ref);
                }
            }
        }
        return offenders.isEmpty() ? null
                : "引用 Host 校验失败（非本 run 证据行）: " + offenders;
    }

    private boolean isRunEvidence(String ref, UUID runId) {
        UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
        return evidence.findById(id)
                .map(row -> row.runId().equals(runId))
                .orElse(false);
    }

    /**
     * 身份面核验（null=通过）：裁决行在案且 APPROVED、回填的 child_task_id 一致、"""
assert old in t
t = t.replace(old, new)

# --- DuplicateKeyException import 可留可去：log 仍用？不再用 → 去掉 import
old = """import org.springframework.dao.DuplicateKeyException;
"""
assert old in t
t = t.replace(old, "")

io.open(p, 'w', encoding='utf-8').write(t)
print('3 ok')

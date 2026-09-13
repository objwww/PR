package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.ReportFeedbackPort;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 终态报告反馈服务（OP-04，后续优化方案 §5.1）：值班人员对<b>已终态 Run 的已
 * 发布报告</b>提交评价——与活跃 Run 命令面（Cancel/Hint 的 isActive 约束，
 * CommandService）完全分开；本服务不放宽任何终态命令约束（FO22）。
 *
 * <p>身份取认证主体（author 由调用方注入，不收自报）；reportDigest 服务端按
 * packageJson 计算，请求可携带期望值对账（报告版本错位=409 冲突而非静默评错
 * 对象，FO29/§5.3"操作前显示评价对象和版本"的服务端半区）。更正 = 新行
 * supersedesId 指向前序（同作者同报告）；同一前序的并发更正一胜一拒、原意见
 * 永不覆盖（FO25）。幂等键 (author, idempotencyKey)：同载荷重放收敛，异载荷
 * 同键显式冲突（FO23）。
 */
public class ReportFeedbackService {

    private static final Logger log =
            LoggerFactory.getLogger(ReportFeedbackService.class);

    private final RcaReportRepository reports;
    private final RcaRunRepository runs;
    private final ReportFeedbackPort feedbacks;
    private final Clock clock;

    public ReportFeedbackService(RcaReportRepository reports, RcaRunRepository runs,
            ReportFeedbackPort feedbacks, Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.feedbacks = Objects.requireNonNull(feedbacks, "feedbacks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 提交结果：replayed=幂等重放既有行；conflict 非 null=并发更正已被他人占位 */
    public record SubmitResult(ReportFeedback stored, boolean replayed, String conflict) {
    }

    /** 报告指纹（评价对象钉面：packageJson 的 sha256） */
    public static String digestOf(RcaReport report) {
        return Digest.sha256Of(report.packageJson()).value();
    }

    public SubmitResult submit(UUID reportId, String expectedReportDigest,
            ReportFeedback.Verdict verdict, String reason, List<String> evidenceRefs,
            UUID supersedesId, String idempotencyKey, String author) {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(author, "author");
        RcaReport report = reports.findById(reportId).orElseThrow(
                () -> new IllegalArgumentException("报告不存在: " + reportId));
        var run = runs.findById(report.runId()).orElseThrow(
                () -> new IllegalStateException("报告所属 run 不存在: " + report.runId()));
        if (run.state().isActive()) {
            throw new IllegalStateException("报告反馈只对终态调查开放（run state="
                    + run.state() + "）——运行中反馈走 Hint 命令面");
        }
        String digest = digestOf(report);
        if (expectedReportDigest != null && !expectedReportDigest.equals(digest)) {
            throw new IllegalStateException("报告版本不一致（期望 " + expectedReportDigest
                    + " 实际 " + digest + "）——请刷新后重评");
        }
        if (supersedesId != null) {
            ReportFeedback predecessor = feedbacks.findById(supersedesId).orElseThrow(
                    () -> new IllegalArgumentException("前序反馈不存在: " + supersedesId));
            if (!predecessor.reportId().equals(reportId)) {
                throw new IllegalArgumentException("前序反馈不属于该报告");
            }
            if (!predecessor.author().equals(author)) {
                throw new IllegalStateException("只能更正本人反馈（不同操作者意见并存"
                        + "——追加新行，不覆盖他人）");
            }
        }
        ReportFeedback candidate = new ReportFeedback(UUID.randomUUID(), reportId,
                report.runId(), digest, author, verdict, reason,
                evidenceRefs == null ? List.of() : evidenceRefs,
                supersedesId, idempotencyKey, clock.instant());
        ReportFeedback stored = feedbacks.insert(candidate);
        if (stored.id().equals(candidate.id())) {
            log.info("报告反馈落档 report={} run={} author={} verdict={} supersedes={}",
                    reportId, report.runId(), author, verdict, supersedesId);
            return new SubmitResult(stored, false, null);
        }
        if (stored.idempotencyKey().equals(candidate.idempotencyKey())) {
            if (stored.verdict() != candidate.verdict()
                    || !stored.reason().equals(candidate.reason())
                    || !stored.evidenceRefs().equals(candidate.evidenceRefs())) {
                throw new IllegalStateException("同幂等键不同载荷——显式冲突（键="
                        + candidate.idempotencyKey() + "）");
            }
            return new SubmitResult(stored, true, null);
        }
        // 同前序已有他人更正占位：既成结果不动，冲突可辨（FO25）
        return new SubmitResult(stored, false,
                "SUPERSEDED_BY:" + stored.id());
    }

    public List<ReportFeedback> byReport(UUID reportId) {
        return feedbacks.findByReportId(reportId);
    }

    public Optional<ReportFeedback> byId(UUID id) {
        return feedbacks.findById(id);
    }
}

package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 评分对象选择规则（M3-16 冻结，评审 P0-7）：selection_policy =
 * <b>final-validated-report-v1</b>——该 RCA Run 的全部报告中取 <b>created_at 最晚</b>
 * 的 STRUCTURE_VALIDATED 报告（同刻并列按 attempt_id 定序）。
 *
 * <p>规则是纯机械的时间序：报告由收尾事务落库，最晚的已验证报告即 Run 状态机走到的
 * 最终答案——evaluator 拿到什么评什么，<b>禁止从多 attempt 中按内容挑最优</b>
 * （防准确率虚高）。无已验证报告 = empty（verdict 由 scorer 按 REJECTED 系与缺席分派）。
 */
public final class FinalReportSelector {

    public static final String SELECTION_POLICY_VERSION = "final-validated-report-v1";

    public Optional<RcaReport> select(List<RcaReport> reports) {
        return reports.stream()
                .filter(r -> r.validationStatus() == ValidationStatus.STRUCTURE_VALIDATED)
                .max(Comparator.comparing(RcaReport::createdAt)
                        .thenComparing(r -> r.attemptId().toString()));
    }
}

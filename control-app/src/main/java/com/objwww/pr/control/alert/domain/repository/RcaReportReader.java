package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.ValidationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * rca_report 只读投影面（RunDetailView 报告 tab；独立于写面 {@link RcaReportRepository}）。
 *
 * <p>与写面仓储的差异：写面 findByRunId 不读 validation_errors（结构验证链列），
 * 本读面补齐该列供 REJECTED 原因链透出；且<b>故意不读 raw_text</b>——Holmes 原文
 * 非前端读面（留表内审计），投影结构上不可能泄出。
 */
public interface RcaReportReader {

    /** 该 run 的全部报告行（重试多行全量返回，最新行取舍归服务层；无行 → 空表） */
    List<RcaReportView> findByRunId(UUID runId);

    /**
     * 报告读投影（字段 = 报告 tab 透出全集）。<b>无 rawText 字段</b>：Holmes 原文
     * 不进读面（审计留 rca_report.raw_text，前端只见六段式 packageJson）。
     */
    record RcaReportView(
            UUID id,
            UUID runId,
            UUID attemptId,
            int schemaVersion,
            ValidationStatus validationStatus,
            List<String> validationErrors,
            String packageJson,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            boolean usageMissing,
            Instant createdAt) {

        public RcaReportView {
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }
    }
}

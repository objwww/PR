package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaReport;

import java.util.List;
import java.util.UUID;

/**
 * rca_report 端口（validation_status 结构验证链落点；只 INSERT+SELECT）。
 */
public interface RcaReportRepository {

    void insert(RcaReport report);

    List<RcaReport> findByRunId(UUID runId);
}

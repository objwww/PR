package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.ReportPublication;

import java.util.Optional;
import java.util.UUID;

/**
 * report_publication 接口（M3-09；control 侧只出生，状态机迁移由 notify-app 投递面驱动）。
 */
public interface ReportPublicationRepository {

    void insert(ReportPublication publication);

    Optional<ReportPublication> findByReportId(UUID reportId);
}

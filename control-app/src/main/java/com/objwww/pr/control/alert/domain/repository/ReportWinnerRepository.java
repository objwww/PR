package com.objwww.pr.control.alert.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * report_generation_winner 端口（M6-04，V33）。发布赢家栅栏：(incident_id, generation)
 * 恰一份 READY publication/outbox——先 INSERT 成功者（CAS 赢家）才可发布（C-68：
 * Native 部分收尾与 Holmes fallback 竞争下防双发）。
 *
 * <p>SQL 契约：{@link #claimWinner} = INSERT ... ON CONFLICT DO NOTHING，行数=1 即
 * 赢家；并发败者拿 0 不报错。同一收尾事务内"claim + publication + outbox"原子
 * （claim 回滚则发布面一并回滚，栅栏不虚占）。insert-only 授权面（V33 revoke
 * update/delete），实现不得提供改/删路径。
 */
public interface ReportWinnerRepository {

    /**
     * 争夺 (incidentId, generation) 的唯一发布权：true = 本报告是赢家（调用方继续
     * publication/outbox）；false = 败者（报告仍落档，只是不发布不通知）。
     */
    boolean claimWinner(UUID incidentId, int generation, UUID reportId, UUID runId,
                        Instant decidedAt);

    /** 观测/测试面：该 (incident, generation) 的赢家报告 */
    Optional<UUID> findWinnerReportId(UUID incidentId, int generation);
}

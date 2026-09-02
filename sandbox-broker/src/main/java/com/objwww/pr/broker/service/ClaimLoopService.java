package com.objwww.pr.broker.service;

import com.objwww.pr.broker.client.ControlApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claim Loop 服务（M4 §4.2 Broker 核心循环）。
 *
 * <p>职责：
 * <ul>
 *   <li>定期轮询 Control API，领取下一个 PENDING 作业</li>
 *   <li>全局并发 1：同时只执行一个作业（uq_sandbox_job_inflight 数据库闸保证）</li>
 *   <li>领取成功后交给 JobExecutor 执行</li>
 * </ul>
 */
@Service
public class ClaimLoopService {

    private static final Logger log = LoggerFactory.getLogger(ClaimLoopService.class);

    private final ControlApiClient controlApiClient;
    private final JobExecutorService jobExecutorService;
    private final String workerId;

    // 执行标志：防止并发执行（同一 Broker 实例内串行）
    private final AtomicBoolean executing = new AtomicBoolean(false);

    public ClaimLoopService(ControlApiClient controlApiClient,
                            JobExecutorService jobExecutorService,
                            String workerId) {
        this.controlApiClient = controlApiClient;
        this.jobExecutorService = jobExecutorService;
        this.workerId = workerId;
    }

    /**
     * 定期领取作业（每 5 秒轮询一次）。
     *
     * <p>全局并发 1 保证：
     * <ul>
     *   <li>数据库侧：uq_sandbox_job_inflight 部分唯一索引（同时 LEASED ≤ 1）</li>
     *   <li>Broker 侧：executing 标志防止同一实例内并发</li>
     * </ul>
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void claimLoop() {
        // 防止并发执行：如果上一个作业还在执行，跳过本次轮询
        if (!executing.compareAndSet(false, true)) {
            log.debug("Skipping claim: previous job still executing");
            return;
        }

        try {
            // 尝试领取下一个 PENDING 作业
            String leaseOwner = workerId + "-" + UUID.randomUUID();
            int leaseDurationSeconds = 300; // 5 分钟租约

            var claimedJobOpt = controlApiClient.claimNext(leaseOwner, leaseDurationSeconds, workerId);

            if (claimedJobOpt.isEmpty()) {
                log.debug("No pending jobs available");
                return;
            }

            var claimedJob = claimedJobOpt.get();
            log.info("Claimed job: jobId={}, leaseEpoch={}", claimedJob.jobId, claimedJob.leaseEpoch);

            // 执行作业（阻塞直到完成）
            jobExecutorService.execute(claimedJob);

        } catch (Exception e) {
            log.error("Claim loop error", e);
        } finally {
            executing.set(false);
        }
    }
}

package com.objwww.pr.broker.service;

import com.objwww.pr.broker.client.ControlApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/**
 * 心跳续租服务（M4 §4.2 Broker 心跳机制）。
 *
 * <p>职责：
 * <ul>
 *   <li>作业执行期间定期向 Control 发送心跳，续租延长 lease_until</li>
 *   <li>心跳失败（网络故障/epoch 不匹配）立即停止作业执行</li>
 *   <li>防止租约过期 → Recovery reaper 回收</li>
 * </ul>
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final ControlApiClient controlApiClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // 当前心跳任务（jobId → ScheduledFuture）
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    public HeartbeatService(ControlApiClient controlApiClient) {
        this.controlApiClient = controlApiClient;
    }

    /**
     * 启动心跳（每 60 秒续租一次，续租 5 分钟）。
     *
     * @param jobId 作业 ID
     * @param leaseEpoch 初始 lease_epoch
     */
    public void startHeartbeat(String jobId, long leaseEpoch) {
        log.info("Starting heartbeat: jobId={}, leaseEpoch={}", jobId, leaseEpoch);

        // 每 60 秒续租一次
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean success = controlApiClient.renewLease(jobId, leaseEpoch, 300);
                if (!success) {
                    log.error("Heartbeat failed (epoch mismatch): jobId={}, leaseEpoch={}", jobId, leaseEpoch);
                    // epoch 不匹配 → 租约已失效 → 停止心跳（作业执行侧需检测并中止）
                    stopHeartbeat(jobId);
                } else {
                    log.debug("Heartbeat success: jobId={}", jobId);
                }
            } catch (Exception e) {
                log.error("Heartbeat error: jobId=" + jobId, e);
            }
        }, 60, 60, TimeUnit.SECONDS);

        heartbeats.put(jobId, future);
    }

    /**
     * 停止心跳。
     *
     * @param jobId 作业 ID
     */
    public void stopHeartbeat(String jobId) {
        ScheduledFuture<?> future = heartbeats.remove(jobId);
        if (future != null) {
            future.cancel(false);
            log.info("Stopped heartbeat: jobId={}", jobId);
        }
    }

    /**
     * 检查心跳是否仍在运行（作业执行侧可定期检查，心跳失败则中止执行）。
     *
     * @param jobId 作业 ID
     * @return true 心跳正常，false 心跳已停止
     */
    public boolean isHeartbeatActive(String jobId) {
        ScheduledFuture<?> future = heartbeats.get(jobId);
        return future != null && !future.isCancelled() && !future.isDone();
    }
}

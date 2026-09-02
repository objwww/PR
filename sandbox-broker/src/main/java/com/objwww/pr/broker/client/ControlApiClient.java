package com.objwww.pr.broker.client;

import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.sandbox.FailureClass;
import com.objwww.pr.shared.sandbox.JobSpec;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.Optional;

/**
 * Control API 客户端（Broker → Control HTTP 通信，零 DB 直连，D1/D17/R1）。
 *
 * <p>核心接口：
 * <ul>
 *   <li>claimNext：领取下一个 PENDING 作业</li>
 *   <li>renewLease：心跳续租</li>
 *   <li>reportSuccess/Failure/Timeout/Rejection：上报终态</li>
 * </ul>
 */
@Component
public class ControlApiClient {

    private final RestTemplate restTemplate;
    private final String controlBaseUrl;

    public ControlApiClient(String controlBaseUrl) {
        this.controlBaseUrl = controlBaseUrl;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 领取下一个 PENDING 作业。
     *
     * @param leaseOwner 租约持有者标识（Broker instance + claim UUID）
     * @param leaseDurationSeconds 租约时长（秒）
     * @param workerId Broker 实例标识
     * @return 已领取的作业（包含 JobSpec），无可领取返回 empty
     */
    public Optional<ClaimedJob> claimNext(String leaseOwner, int leaseDurationSeconds, String workerId) {
        String url = controlBaseUrl + "/api/sandbox/jobs/claim";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("leaseOwner", leaseOwner);
        request.put("leaseDurationSeconds", leaseDurationSeconds);
        request.put("workerId", workerId);

        try {
            ClaimedJob job = restTemplate.postForObject(url, request, ClaimedJob.class);
            return Optional.ofNullable(job);
        } catch (Exception e) {
            // 无可领取作业或网络错误
            return Optional.empty();
        }
    }

    /**
     * 心跳续租。
     *
     * @param jobId 作业 ID
     * @param expectedEpoch 预期的 lease_epoch（CAS fencing）
     * @param leaseDurationSeconds 续租时长（秒）
     * @return true 续租成功，false epoch 不匹配（租约已失效）
     */
    public boolean renewLease(String jobId, long expectedEpoch, int leaseDurationSeconds) {
        String url = controlBaseUrl + "/api/sandbox/jobs/" + jobId + "/renew";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("expectedEpoch", expectedEpoch);
        request.put("leaseDurationSeconds", leaseDurationSeconds);

        try {
            RenewLeaseResponse response = restTemplate.postForObject(url, request, RenewLeaseResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上报作业成功完成。
     */
    public boolean reportSuccess(String jobId, long expectedEpoch, String containerId, int exitCode,
                                 Digest observationDigest, String observationSummary,
                                 long observationBytes, boolean truncated,
                                 Digest resultDigest, Digest logDigest) {
        String url = controlBaseUrl + "/api/sandbox/jobs/" + jobId + "/success";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("expectedEpoch", expectedEpoch);
        request.put("containerId", containerId);
        request.put("exitCode", exitCode);
        request.put("observationDigest", observationDigest.hex());
        request.put("observationSummary", observationSummary);
        request.put("observationBytes", observationBytes);
        request.put("truncated", truncated);
        request.put("resultDigest", resultDigest.hex());
        request.put("logDigest", logDigest.hex());

        try {
            ReportResponse response = restTemplate.postForObject(url, request, ReportResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上报作业失败。
     */
    public boolean reportFailure(String jobId, long expectedEpoch, String containerId, int exitCode,
                                 Digest observationDigest, String observationSummary,
                                 long observationBytes, boolean truncated, Digest logDigest,
                                 String errorCode, String sanitizedMessage, FailureClass failureClass) {
        String url = controlBaseUrl + "/api/sandbox/jobs/" + jobId + "/failure";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("expectedEpoch", expectedEpoch);
        request.put("containerId", containerId != null ? containerId : "");
        request.put("exitCode", exitCode);
        request.put("observationDigest", observationDigest != null ? observationDigest.hex() : "");
        request.put("observationSummary", observationSummary);
        request.put("observationBytes", observationBytes);
        request.put("truncated", truncated);
        request.put("logDigest", logDigest != null ? logDigest.hex() : "");
        request.put("errorCode", errorCode);
        request.put("sanitizedMessage", sanitizedMessage);
        request.put("failureClass", failureClass.name());

        try {
            ReportResponse response = restTemplate.postForObject(url, request, ReportResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上报作业超时。
     */
    public boolean reportTimeout(String jobId, long expectedEpoch, String containerId, Digest logDigest) {
        String url = controlBaseUrl + "/api/sandbox/jobs/" + jobId + "/timeout";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("expectedEpoch", expectedEpoch);
        request.put("containerId", containerId);
        request.put("logDigest", logDigest.hex());

        try {
            ReportResponse response = restTemplate.postForObject(url, request, ReportResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 上报策略拒绝。
     */
    public boolean reportRejection(String jobId, long expectedEpoch, String reason) {
        String url = controlBaseUrl + "/api/sandbox/jobs/" + jobId + "/rejection";

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("expectedEpoch", expectedEpoch);
        request.put("reason", reason);

        try {
            ReportResponse response = restTemplate.postForObject(url, request, ReportResponse.class);
            return response != null && response.success;
        } catch (Exception e) {
            return false;
        }
    }

    // DTO 类

    public static class ClaimedJob {
        public String jobId;
        public String toolCallId;
        public long leaseEpoch;
        public JobSpec jobSpec;
    }

    public static class RenewLeaseResponse {
        public boolean success;
    }

    public static class ReportResponse {
        public boolean success;
    }
}

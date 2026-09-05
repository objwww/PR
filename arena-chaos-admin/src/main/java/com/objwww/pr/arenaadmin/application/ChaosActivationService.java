package com.objwww.pr.arenaadmin.application;

import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.Activation;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.BackfillResult;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.GtFields;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.SessionRow;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * chaos 激活/关闭应用服务（M2-17）：请求校验（TTL 边界、digest 形态、fingerprint 可算）
 * → 存储层单事务激活。唯一约束冲突（scenario 重复 / 同型同靶已 ACTIVE）原样上抛，
 * 接口层映射 409。TTL reaper 由运行时循环驱动（{@link #reaperTick}）。
 */
public class ChaosActivationService {

    /** 请求校验失败（接口层 → 400） */
    public static class InvalidRequestException extends IllegalArgumentException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SCENARIO_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,63}$");

    private final PostgresChaosAdminStore store;
    private final int ttlMinSeconds;
    private final int ttlMaxSeconds;

    public ChaosActivationService(PostgresChaosAdminStore store,
                                  int ttlMinSeconds, int ttlMaxSeconds) {
        this.store = store;
        this.ttlMinSeconds = ttlMinSeconds;
        this.ttlMaxSeconds = ttlMaxSeconds;
    }

    /** @throws DataIntegrityViolationException 场景重复/并发同靶激活（DB 约束） */
    public Activation activate(String faultType, String scenarioId, String target,
                               Integer ttlSeconds, String operator, String configDigest,
                               GtFields groundTruth, Map<String, String> alertLabels,
                               String ruleDigest) {
        if (faultType == null || !faultType.matches("F[123]")) {
            throw new InvalidRequestException("faultType 必须是 F1/F2/F3");
        }
        if (scenarioId == null || !SCENARIO_ID.matcher(scenarioId).matches()) {
            throw new InvalidRequestException("scenarioId 形态非法（[a-z0-9][a-z0-9-]{2,63}）");
        }
        int ttl = ttlSeconds == null ? 0 : ttlSeconds;
        if (ttl < ttlMinSeconds || ttl > ttlMaxSeconds) {
            throw new InvalidRequestException(
                    "ttlSeconds 越界（%d..%d）".formatted(ttlMinSeconds, ttlMaxSeconds));
        }
        if (operator == null || operator.isBlank()) {
            throw new InvalidRequestException("operator 必填（审计面）");
        }
        if (configDigest == null || !HEX64.matcher(configDigest).matches()) {
            throw new InvalidRequestException("configDigest 必须是 64 位十六进制（sha256）");
        }
        if (ruleDigest == null || !HEX64.matcher(ruleDigest).matches()) {
            throw new InvalidRequestException("ruleDigest 必须是 64 位十六进制（sha256）");
        }
        if (groundTruth == null || groundTruth.datasetVersion() == null
                || groundTruth.payloadDigest() == null
                || !HEX64.matcher(groundTruth.payloadDigest()).matches()
                || groundTruth.applicableScope() == null
                || groundTruth.schemaVersion() == null || groundTruth.schemaVersion() < 1) {
            throw new InvalidRequestException("groundTruth 冻结字段不完整（C-5）");
        }
        if (alertLabels == null || alertLabels.isEmpty()
                || !alertLabels.containsKey("alertname")) {
            throw new InvalidRequestException("alertLabels 必含 alertname（指纹输入面）");
        }
        return store.activate(scenarioId, faultType, target, ttl, operator, configDigest,
                groundTruth, alertLabels, ruleDigest);
    }

    /** @return false = CAS 未中（409） */
    public boolean deactivate(String scenarioId, Long expectedGeneration) {
        if (scenarioId == null || expectedGeneration == null) {
            throw new InvalidRequestException("scenarioId/expectedGeneration 必填");
        }
        return store.casRecovering(scenarioId, expectedGeneration);
    }

    /** 状态查询（会话 + 注入审计摘要）；@return null = 未知场景 */
    public record StatusView(SessionRow session, Map<String, Integer> audit) {
    }

    public StatusView status(String scenarioId) {
        return store.findSession(scenarioId)
                .map(s -> new StatusView(s, store.auditSummary(s.id())))
                .orElse(null);
    }

    /** @return null = 会话不存在或事件代数过期（不串场） */
    public BackfillResult backfillIncident(String scenarioId, String incidentId,
                                           Long incidentGeneration, String runId,
                                           String reportId) {
        if (scenarioId == null || incidentId == null || incidentGeneration == null) {
            throw new InvalidRequestException(
                    "scenarioId/incidentId/incidentGeneration 必填");
        }
        return store.backfillIncident(scenarioId, incidentId, incidentGeneration,
                runId, reportId);
    }

    /** TTL 清扫 + 恢复收口 + 启动孤儿清扫（reaper 循环每轮） */
    public int reaperTick() {
        int expired = store.reapExpired();
        int closed = store.closeRecovered();
        int orphaned = store.reapStartupOrphans();
        return expired + closed + orphaned;
    }
}

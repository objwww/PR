package com.objwww.pr.control.domain.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 账本条目（§4.1/I29）：两段记账 STARTED → 终态。
 */
public final class ModelCallLedgerEntry {
    private final UUID id;
    private final UUID invocationId;
    private final int callSeq;
    private final UUID reviewRunId;
    private final UUID runStepId;
    private final UUID attemptId;
    private final long leaseEpoch;
    private final String routeId;
    private final String routeRole;
    private final String fallbackFrom;
    private final String endpointScope;
    private final String quotaScope;
    private final String requestedModel;
    private String reportedModel;
    private String providerRequestId;
    private String state; // STARTED/SUCCEEDED/FAILED/UNKNOWN
    private String outcome; // OK/TIMEOUT/...
    private Integer httpStatus;
    private Long retryAfterMs;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private boolean usageMissing;
    private Long latencyMs;
    private Long costMicros;
    private String pricingVersion;
    private String currency;
    private Long inputPriceMicrosPerK;
    private Long outputPriceMicrosPerK;
    private String errorCode;
    private String errorFingerprint;
    private String sanitizedMessage;
    private final Instant startedAt;
    private Instant finishedAt;

    private ModelCallLedgerEntry(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.invocationId = Objects.requireNonNull(builder.invocationId, "invocationId");
        this.callSeq = builder.callSeq;
        this.reviewRunId = Objects.requireNonNull(builder.reviewRunId, "reviewRunId");
        this.runStepId = Objects.requireNonNull(builder.runStepId, "runStepId");
        this.attemptId = Objects.requireNonNull(builder.attemptId, "attemptId");
        this.leaseEpoch = builder.leaseEpoch;
        this.routeId = Objects.requireNonNull(builder.routeId, "routeId");
        this.routeRole = Objects.requireNonNull(builder.routeRole, "routeRole");
        this.fallbackFrom = builder.fallbackFrom;
        this.endpointScope = Objects.requireNonNull(builder.endpointScope, "endpointScope");
        this.quotaScope = Objects.requireNonNull(builder.quotaScope, "quotaScope");
        this.requestedModel = Objects.requireNonNull(builder.requestedModel, "requestedModel");
        this.state = "STARTED";
        this.startedAt = Instant.now(); // DB default now()
        this.usageMissing = false;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public UUID id() { return id; }
    public UUID invocationId() { return invocationId; }
    public int callSeq() { return callSeq; }
    public UUID reviewRunId() { return reviewRunId; }
    public UUID runStepId() { return runStepId; }
    public UUID attemptId() { return attemptId; }
    public long leaseEpoch() { return leaseEpoch; }
    public String routeId() { return routeId; }
    public String routeRole() { return routeRole; }
    public String fallbackFrom() { return fallbackFrom; }
    public String endpointScope() { return endpointScope; }
    public String quotaScope() { return quotaScope; }
    public String requestedModel() { return requestedModel; }
    public String reportedModel() { return reportedModel; }
    public String providerRequestId() { return providerRequestId; }
    public String state() { return state; }
    public String outcome() { return outcome; }
    public Integer httpStatus() { return httpStatus; }
    public Long retryAfterMs() { return retryAfterMs; }
    public int promptTokens() { return promptTokens; }
    public int completionTokens() { return completionTokens; }
    public int totalTokens() { return totalTokens; }
    public boolean usageMissing() { return usageMissing; }
    public Long latencyMs() { return latencyMs; }
    public Long costMicros() { return costMicros; }
    public String pricingVersion() { return pricingVersion; }
    public String currency() { return currency; }
    public Long inputPriceMicrosPerK() { return inputPriceMicrosPerK; }
    public Long outputPriceMicrosPerK() { return outputPriceMicrosPerK; }
    public String errorCode() { return errorCode; }
    public String errorFingerprint() { return errorFingerprint; }
    public String sanitizedMessage() { return sanitizedMessage; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    public static final class Builder {
        private UUID id;
        private UUID invocationId;
        private int callSeq;
        private UUID reviewRunId;
        private UUID runStepId;
        private UUID attemptId;
        private long leaseEpoch;
        private String routeId;
        private String routeRole;
        private String fallbackFrom;
        private String endpointScope;
        private String quotaScope;
        private String requestedModel;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder invocationId(UUID invocationId) { this.invocationId = invocationId; return this; }
        public Builder callSeq(int callSeq) { this.callSeq = callSeq; return this; }
        public Builder reviewRunId(UUID reviewRunId) { this.reviewRunId = reviewRunId; return this; }
        public Builder runStepId(UUID runStepId) { this.runStepId = runStepId; return this; }
        public Builder attemptId(UUID attemptId) { this.attemptId = attemptId; return this; }
        public Builder leaseEpoch(long leaseEpoch) { this.leaseEpoch = leaseEpoch; return this; }
        public Builder routeId(String routeId) { this.routeId = routeId; return this; }
        public Builder routeRole(String routeRole) { this.routeRole = routeRole; return this; }
        public Builder fallbackFrom(String fallbackFrom) { this.fallbackFrom = fallbackFrom; return this; }
        public Builder endpointScope(String endpointScope) { this.endpointScope = endpointScope; return this; }
        public Builder quotaScope(String quotaScope) { this.quotaScope = quotaScope; return this; }
        public Builder requestedModel(String requestedModel) { this.requestedModel = requestedModel; return this; }

        public ModelCallLedgerEntry build() {
            return new ModelCallLedgerEntry(this);
        }
    }
}

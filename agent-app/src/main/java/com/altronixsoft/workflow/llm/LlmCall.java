package com.altronixsoft.workflow.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One audited model call. Request and response hold the prompt and completion text. */
@Entity
@Table(name = "llm_call")
public class LlmCall {

    @Id
    private UUID id;

    @Column(name = "instance_id")
    private UUID instanceId;

    @Column(name = "step_execution_id")
    private UUID stepExecutionId;

    private String model;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "cost_eur")
    private BigDecimal costEur;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(nullable = false)
    private String request;

    private String response;

    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LlmCall() {}

    LlmCall(
            UUID instanceId,
            UUID stepExecutionId,
            String model,
            String promptVersion,
            Integer promptTokens,
            Integer completionTokens,
            BigDecimal costEur,
            long latencyMs,
            String request,
            String response,
            String error,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.instanceId = instanceId;
        this.stepExecutionId = stepExecutionId;
        this.costEur = costEur;
        this.model = model;
        this.promptVersion = promptVersion;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.request = request;
        this.response = response;
        this.error = error;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public UUID getStepExecutionId() {
        return stepExecutionId;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public BigDecimal getCostEur() {
        return costEur;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getRequest() {
        return request;
    }

    public String getResponse() {
        return response;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

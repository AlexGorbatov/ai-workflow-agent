package com.altronixsoft.workflow.tools;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The record of one gateway call: what was asked, what came back, how long it took. */
@Entity
@Table(name = "tool_call")
public class ToolCall {

    @Id
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(name = "step_execution_id")
    private UUID stepExecutionId;

    @Column(nullable = false)
    private String tool;

    @Enumerated(EnumType.STRING)
    private ToolKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> args;

    private String result;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ToolCallStatus status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ToolCall() {}

    ToolCall(
            UUID instanceId,
            UUID stepExecutionId,
            String tool,
            ToolKind kind,
            Map<String, Object> args,
            String result,
            ToolCallStatus status,
            String idempotencyKey,
            long durationMs,
            String error,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.instanceId = instanceId;
        this.stepExecutionId = stepExecutionId;
        this.tool = tool;
        this.kind = kind;
        this.args = args;
        this.result = result;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.durationMs = durationMs;
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

    public String getTool() {
        return tool;
    }

    public ToolKind getKind() {
        return kind;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public String getResult() {
        return result;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
